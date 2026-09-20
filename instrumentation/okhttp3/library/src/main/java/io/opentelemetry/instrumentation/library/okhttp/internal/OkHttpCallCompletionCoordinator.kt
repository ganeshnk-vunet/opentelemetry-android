/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.library.okhttp.OkHttpInstrumentation
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Owns the end of every OkHttp `http.client` span.
 *
 * A span is started by [TimingTracingInterceptor] inside a *network* interceptor and is ended here,
 * from OkHttp's `EventListener`. The listener is the only source of the response-body events the
 * `http.client.timing.*` attributes are built from, which is why completion is deferred to it at
 * all.
 *
 * Two properties matter and are easy to lose:
 *
 * 1. **A span must end close to when the request actually finished.** OkHttp reports `callEnd` when
 *    the response body reaches EOF *or is closed*, whichever comes first. A body read to
 *    exhaustion therefore ends its span promptly, but one that is never read -- an SSE stream, a
 *    long poll, or a body the caller simply drops -- reports nothing until it is closed, and the
 *    span stretches across the whole of that wait. Production spans of up to 23 hours came from
 *    exactly this. The watchdog below bounds it.
 * 2. **A span must end at all.** A redirect or retry re-entering [registerTraced] completes the
 *    previous attempt rather than overwriting it, and the watchdog ends anything that outlives its
 *    deadline. Without that backstop a call whose body is never touched leaks its `Call`,
 *    `Response`, `Context` and `Span` for the life of the process. Timing state is per-`Call`, so a
 *    re-registration must end the previous span *without* consuming the store -- otherwise the
 *    surviving attempt loses every `http.client.timing.*` attribute. [completePending] writes
 *    timing attributes first (they are dropped after `Span.end`) and always ends the span in a
 *    `finally`, including via `span.end()` if [Instrumenter.end] throws, so a throwing enricher
 *    cannot strand a started span.
 *
 * ### Why the pending map holds strong references, and what that costs
 *
 * A pending entry pins its `Call` until the call completes or the watchdog expires it. OkHttp tracks
 * its own in-flight calls as `RealConnection.calls: List<Reference<RealCall>>` -- *weak* references,
 * which is how it notices an abandoned response body and reclaims the socket. Our strong reference
 * suppresses that detection, so for a leaked body the socket is reclaimed up to one cap later than
 * it otherwise would be. That is the price of deferring completion at all, and the cap is what
 * bounds it; before the watchdog existed the suppression was permanent.
 *
 * **Weak references cannot be substituted here, and trying is actively worse.** The map's *value*
 * reaches its own *key* by two independent strong paths: `PendingTrace.chain` is a
 * `RealInterceptorChain`, which holds `private final RealCall call`, and `PendingTrace.response` is
 * a `Response`, which holds `private final Exchange exchange`, which holds the same `RealCall`. A
 * `WeakHashMap` keyed on `Call` would therefore never evict -- an entry keeps its own key strongly
 * reachable -- turning today's bounded retention into a permanent one. A `ReferenceQueue` over
 * `WeakReference<Call>` fails for the same reason: the referent stays strongly reachable, nothing is
 * ever enqueued, and a queue-draining thread would block forever. Both would first need `chain` and
 * `response` released, and both are required to extract attributes at `Instrumenter.end`.
 */
internal object OkHttpCallCompletionCoordinator {
    private class PendingTrace(
        val context: Context,
        val chain: Interceptor.Chain,
        val span: Span,
        val budgetNanos: Long,
        @Volatile var deadlineNanos: Long,
        @Volatile var response: Response? = null,
        @Volatile var error: Throwable? = null,
    )

    private val pendingTraces = ConcurrentHashMap<Call, PendingTrace>()

    @Volatile private var instrumenter: Instrumenter<Interceptor.Chain, Response>? = null

    @Volatile private var spanEnricher: OkHttpTimingSpanEnricher? = null

    @Volatile private var maxCallDurationNanos: Long =
        TimeUnit.MILLISECONDS.toNanos(OkHttpInstrumentation.DEFAULT_MAX_CALL_DURATION_MILLIS)

    @Volatile private var nanoTimeSource: () -> Long = { System.nanoTime() }

    @Volatile private var watchdog: ScheduledExecutorService? = null

    @Volatile private var watchdogTask: ScheduledFuture<*>? = null

    @Volatile private var ownsWatchdog: Boolean = false

    fun configure(
        instrumenter: Instrumenter<Interceptor.Chain, Response>,
        spanEnricher: OkHttpTimingSpanEnricher,
        maxCallDurationMillis: Long = OkHttpInstrumentation.DEFAULT_MAX_CALL_DURATION_MILLIS,
    ) {
        this.instrumenter = instrumenter
        this.spanEnricher = spanEnricher
        this.maxCallDurationNanos = TimeUnit.MILLISECONDS.toNanos(maxCallDurationMillis)
        startWatchdog(maxCallDurationMillis)
    }

    fun registerTraced(
        call: Call,
        context: Context,
        chain: Interceptor.Chain,
        span: Span,
    ) {
        // A network interceptor runs once per wire attempt, so a redirect or an auth retry brings
        // the same Call back here. Overwriting the entry would strand the previous attempt's span
        // with no path to end(); the previous response is finished by definition once a new attempt
        // has begun, so close it out and give each attempt its own correctly ended span.
        //
        // Only an existing entry is completed. Routing the first attempt through complete() would
        // take its untraced branch and discard the timing state that callStart has already
        // recorded, losing every http.client.timing.* attribute on the call.
        pendingTraces.remove(call)?.let { previous ->
            // Timing is stored per Call, not per attempt. Consuming it here would strip
            // http.client.timing.* from the surviving attempt -- a regression against the leak
            // this path exists to close, where the final span still received callEnd enrichment.
            completePending(call, previous, abandoned = false, consumeTiming = false)
        }
        val budgetNanos = budgetNanosFor(call)
        pendingTraces[call] =
            PendingTrace(context, chain, span, budgetNanos, nanoTimeSource() + budgetNanos)
    }

    fun setResponse(
        call: Call,
        response: Response,
    ) {
        val pending = pendingTraces[call] ?: return
        pending.response = response
        // A network interceptor's chain.proceed() returns once the response *headers* have
        // arrived, so this is the boundary between two phases that fail for entirely different
        // reasons: before it the app is waiting on the server, after it the app is reading -- or
        // failing to read -- the body. Giving the body its own budget from here means a slow
        // server and a slow download are each measured in full rather than sharing one clock and
        // truncating whichever happens second. Both phases stay bounded, so nothing becomes
        // unbounded: a call that never gets headers is still cut off by the budget set at
        // registration.
        pending.deadlineNanos = nanoTimeSource() + pending.budgetNanos
    }

    fun setError(
        call: Call,
        error: Throwable,
    ) {
        pendingTraces[call]?.error = error
    }

    /**
     * Ends a span without going through the pending map, for the case where no `EventListener`
     * could be installed and nothing would otherwise ever complete it. See
     * [OkHttpSingletons.eventListenerWiringFailed].
     */
    fun endImmediately(
        context: Context,
        chain: Interceptor.Chain,
        response: Response?,
        error: Throwable?,
    ) {
        instrumenter?.end(context, chain, response, error)
    }

    fun onCallEnd(call: Call) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.callEndNanos = nanoTimeSource()
        }
        complete(call)
    }

    fun onCallFailed(
        call: Call,
        error: IOException,
    ) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.failed = true
            state.phasesComplete = false
            state.callEndNanos = nanoTimeSource()
        }
        pendingTraces[call]?.error = error
        complete(call)
    }

    fun onCanceled(call: Call) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.failed = true
            state.phasesComplete = false
        }
        pendingTraces[call]?.error = IOException("Canceled")
        complete(call)
    }

    private fun complete(call: Call) {
        val pending = pendingTraces.remove(call)
        if (pending == null) {
            OkHttpCallTimingStore.discard(call)
            return
        }
        completePending(call, pending, abandoned = false, consumeTiming = true)
    }

    /**
     * Ends [span] only if it is still the in-flight attempt for [call]. A redirect replaces the
     * map value under the same `Call` key; completing by key alone would abandon the new attempt.
     */
    internal fun abandonIfCurrent(
        call: Call,
        span: Span,
    ): Boolean {
        val pending = pendingTraces[call] ?: return false
        if (pending.span !== span) {
            return false
        }
        if (nanoTimeSource() - pending.deadlineNanos < 0) {
            return false
        }
        if (!pendingTraces.remove(call, pending)) {
            return false
        }
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.failed = true
            state.phasesComplete = false
        }
        completePending(call, pending, abandoned = true, consumeTiming = true)
        return true
    }

    private fun completePending(
        call: Call,
        pending: PendingTrace,
        abandoned: Boolean,
        consumeTiming: Boolean,
    ) {
        try {
            if (abandoned) {
                pending.span.setAttribute(OkHttpTimingAttributes.ABANDONED, true)
            }
            if (consumeTiming) {
                try {
                    val enricher = spanEnricher
                    if (enricher != null) {
                        enricher.enrich(pending.span, call)
                    } else {
                        OkHttpCallTimingStore.discard(call)
                    }
                } catch (_: RuntimeException) {
                    OkHttpCallTimingStore.discard(call)
                }
            } else {
                pending.span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, false)
            }
        } finally {
            // Timing attributes must be written before end -- they are dropped afterwards -- but
            // ending is not optional. Instrumenter.end throwing used to leave the span started
            // after it had already been removed from the pending map, with nothing to retry.
            try {
                val inst = instrumenter
                if (inst != null) {
                    // Response is reported even alongside an error. TimingTracingInterceptor sets
                    // it as soon as chain.proceed() returns, so a call that received a 200 and
                    // then failed while streaming the body still has one. Both are passed so the
                    // span carries the status code *and* the error.
                    inst.end(pending.context, pending.chain, pending.response, pending.error)
                } else {
                    pending.span.end()
                }
            } catch (_: RuntimeException) {
                try {
                    pending.span.end()
                } catch (_: RuntimeException) {
                    // last resort; sweepGuarded still swallows so the watchdog survives
                }
            }
        }
    }

    /**
     * OkHttp's own `callTimeout` is the application's statement of the longest call it considers
     * legitimate, so honour it when set. It defaults to 0, meaning no timeout, in which case the
     * configured cap applies. Read through the public [Call.timeout] rather than by reflection, so
     * minification cannot break it.
     */
    private fun budgetNanosFor(call: Call): Long {
        val callTimeoutNanos =
            try {
                call.timeout().timeoutNanos()
            } catch (_: RuntimeException) {
                0L
            }
        return if (callTimeoutNanos > 0L) callTimeoutNanos else maxCallDurationNanos
    }

    private fun startWatchdog(maxCallDurationMillis: Long) {
        if (watchdogTask != null) {
            return
        }
        val executor =
            watchdog ?: Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "otel-okhttp-call-watchdog").apply { isDaemon = true }
                }.also {
                    watchdog = it
                    ownsWatchdog = true
                }
        // Fixed delay, not fixed rate: when a cached Android process becomes uncached, a
        // fixed-rate task fires once for every interval it slept through, all at once.
        val intervalMillis = sweepIntervalMillis(maxCallDurationMillis)
        watchdogTask =
            executor.scheduleWithFixedDelay(
                ::sweepGuarded,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS,
            )
    }

    /**
     * A repeating scheduled task is suppressed permanently the first time it throws, with no
     * notification. One transient failure while ending a span would otherwise disable the watchdog
     * for the life of the process, which is precisely the state it exists to prevent, so nothing is
     * allowed to escape.
     */
    internal fun sweepGuarded() {
        try {
            sweep()
        } catch (_: Throwable) {
            // Deliberately swallowed; see above.
        }
    }

    internal fun sweep() {
        val now = nanoTimeSource()
        for ((call, pending) in pendingTraces) {
            // Subtraction, not `now >= deadline`, so a nanoTime wraparound does not expire
            // every in-flight call at once. Completing by identity, not by Call key: a redirect
            // replaces the map value, and a stale iterator snapshot must not end the new attempt.
            if (now - pending.deadlineNanos >= 0) {
                abandonIfCurrent(call, pending.span)
            }
        }
        // Calls that never started a span still leave timing state behind -- websocket upgrades
        // reach the EventListener but not the network interceptor, so nothing ever collects theirs.
        // In-flight DNS/connect is the same "not in pendingTraces" shape and must not be reclaimed.
        OkHttpCallTimingStore.discardOlderThan(now - maxCallDurationNanos) {
            !pendingTraces.containsKey(it)
        }
    }

    /** Number of spans awaiting completion; used by tests to assert nothing is stranded. */
    internal val pendingCount: Int
        get() = pendingTraces.size

    internal fun setNanoTimeSource(source: () -> Long) {
        nanoTimeSource = source
    }

    internal fun setWatchdogScheduler(scheduler: ScheduledExecutorService?) {
        stopWatchdog()
        watchdog = scheduler
        ownsWatchdog = false
    }

    private fun stopWatchdog() {
        watchdogTask?.cancel(false)
        watchdogTask = null
        if (ownsWatchdog) {
            watchdog?.shutdownNow()
            watchdog = null
            ownsWatchdog = false
        }
    }

    private fun sweepIntervalMillis(maxCallDurationMillis: Long): Long =
        (maxCallDurationMillis / SWEEPS_PER_WINDOW)
            .coerceIn(MIN_SWEEP_INTERVAL_MILLIS, MAX_SWEEP_INTERVAL_MILLIS)

    fun clear() {
        stopWatchdog()
        pendingTraces.clear()
        nanoTimeSource = { System.nanoTime() }
        maxCallDurationNanos =
            TimeUnit.MILLISECONDS.toNanos(OkHttpInstrumentation.DEFAULT_MAX_CALL_DURATION_MILLIS)
    }

    private const val SWEEPS_PER_WINDOW = 6L
    private const val MIN_SWEEP_INTERVAL_MILLIS = 1_000L
    private const val MAX_SWEEP_INTERVAL_MILLIS = 10_000L
}
