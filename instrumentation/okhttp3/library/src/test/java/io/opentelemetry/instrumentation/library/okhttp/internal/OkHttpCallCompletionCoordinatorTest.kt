/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import java.io.IOException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpCallCompletionCoordinatorTest {
    private lateinit var call: Call
    private lateinit var span: Span
    private lateinit var instrumenter: Instrumenter<Interceptor.Chain, Response>
    private lateinit var chain: Interceptor.Chain

    @BeforeEach
    fun setUp() {
        call = mockk(relaxed = true)
        span = mockk(relaxed = true)
        chain = mockk(relaxed = true)
        instrumenter = mockk(relaxed = true)
        every { span.setAttribute(any<String>(), any<Long>()) } returns span
        every { span.setAttribute(any<String>(), any<Boolean>()) } returns span
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
        OkHttpCallCompletionCoordinator.configure(instrumenter, OkHttpTimingSpanEnricher())
    }

    @AfterEach
    fun tearDown() {
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
    }

    @Test
    fun `onCallEnd finalizes timing after response body and ends traced span`() {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.callStartNanos = 0L
        state.responseHeadersStartNanos = 10_000_000L
        state.responseBodyEndNanos = 30_000_000L
        val response = mockk<Response>(relaxed = true)
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)
        OkHttpCallCompletionCoordinator.setResponse(call, response)

        OkHttpCallCompletionCoordinator.onCallEnd(call)

        verify { span.setAttribute(OkHttpTimingAttributes.DOWNLOAD_MS, 20L) }
        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, true) }
        verify { instrumenter.end(Context.root(), chain, response, null) }
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }

    @Test
    fun `onCallEnd discards timing for untraced calls`() {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L

        OkHttpCallCompletionCoordinator.onCallEnd(call)

        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
        verify(exactly = 0) { instrumenter.end(any(), any(), any(), any()) }
    }

    @Test
    fun `configure schedules the completion watchdog`() {
        val scheduler = mockk<ScheduledExecutorService>(relaxed = true)
        OkHttpCallCompletionCoordinator.clear()
        OkHttpCallCompletionCoordinator.setWatchdogScheduler(scheduler)

        OkHttpCallCompletionCoordinator.configure(instrumenter, OkHttpTimingSpanEnricher(), 60_000L)

        // Without this the watchdog is never started and no cap is enforced at runtime, however
        // correct sweep() itself is.
        verify {
            scheduler.scheduleWithFixedDelay(any(), 10_000L, 10_000L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `a sweep that throws does not escape and kill the watchdog`() {
        // scheduleAtFixedRate silently suppresses a repeating task for the life of the process the
        // first time it throws, which would disable the very backstop this class exists to provide.
        every { instrumenter.end(any(), any(), any(), any()) } throws RuntimeException("boom")
        OkHttpCallCompletionCoordinator.setNanoTimeSource { 0L }
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)
        OkHttpCallCompletionCoordinator.setNanoTimeSource { TimeUnit.SECONDS.toNanos(301) }

        OkHttpCallCompletionCoordinator.sweepGuarded()

        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()
        // instrumenter.end threw, but the span must still be closed -- pendingCount==0 without
        // end() used to lock in a stranded, never-exported span.
        verify { span.end() }
    }

    /**
     * A slow server and a slow body read fail for different reasons and must not share one clock.
     * Headers arriving late already consume the request budget; if the body were then measured
     * against that same deadline it would be truncated almost immediately, turning a legitimate
     * transfer into an abandoned one. End to end this is invisible, because MockWebServer returns
     * headers instantly -- the phases have to be driven apart explicitly.
     */
    @Test
    fun `the body gets its own budget once the headers arrive`() {
        var now = 0L
        OkHttpCallCompletionCoordinator.setNanoTimeSource { now }
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        // Server takes 200s to send headers, well into the 300s request budget.
        now = TimeUnit.SECONDS.toNanos(200)
        OkHttpCallCompletionCoordinator.setResponse(call, mockk(relaxed = true))

        // 400s total: past the deadline set at registration, but only 200s into reading the body.
        now = TimeUnit.SECONDS.toNanos(400)
        OkHttpCallCompletionCoordinator.sweepGuarded()

        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)
        verify(exactly = 0) { instrumenter.end(any(), any(), any(), any()) }

        // The body phase is bounded too -- it is re-based, not removed.
        now = TimeUnit.SECONDS.toNanos(520)
        OkHttpCallCompletionCoordinator.sweepGuarded()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()
    }

    /**
     * The pre-headers phase keeps a backstop. Skipping calls that have no response yet -- on the
     * theory that OkHttp's own timeouts will handle them -- leaves a call unbounded whenever the
     * app disables them, which is common for streaming and long-polling clients.
     */
    @Test
    fun `a call that never receives headers is still bounded`() {
        var now = 0L
        OkHttpCallCompletionCoordinator.setNanoTimeSource { now }
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        now = TimeUnit.SECONDS.toNanos(301)
        OkHttpCallCompletionCoordinator.sweepGuarded()

        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()
        verify { instrumenter.end(any(), any(), any(), any()) }
    }

    @Test
    fun `re-registering the same call ends the previous attempt's span`() {
        val firstSpan = mockk<Span>(relaxed = true)
        val firstChain = mockk<Interceptor.Chain>(relaxed = true)
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), firstChain, firstSpan)

        // A network interceptor runs once per wire attempt, so a redirect or an auth retry brings
        // the same Call back. Overwriting would strand the first span with no path to end().
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        verify { instrumenter.end(Context.root(), firstChain, null, null) }
        verify { firstSpan.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, false) }
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)
    }

    @Test
    fun `re-registering leaves timing for the surviving attempt`() {
        val firstSpan = mockk<Span>(relaxed = true)
        val firstChain = mockk<Interceptor.Chain>(relaxed = true)
        val state = OkHttpCallTimingStore.stateFor(call)
        state.callStartNanos = 0L
        state.responseHeadersStartNanos = 10_000_000L
        state.responseBodyEndNanos = 30_000_000L

        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), firstChain, firstSpan)
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)
        OkHttpCallCompletionCoordinator.onCallEnd(call)

        // The first attempt must not consume the per-Call timing row.
        verify(exactly = 0) { firstSpan.setAttribute(OkHttpTimingAttributes.DOWNLOAD_MS, 20L) }
        verify { span.setAttribute(OkHttpTimingAttributes.DOWNLOAD_MS, 20L) }
    }

    @Test
    fun `sweep does not abandon a newer attempt when an older snapshot expires`() {
        val firstSpan = mockk<Span>(relaxed = true)
        val firstChain = mockk<Interceptor.Chain>(relaxed = true)
        OkHttpCallCompletionCoordinator.setNanoTimeSource { 0L }
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), firstChain, firstSpan)

        // Deadline of the first attempt has passed, then a redirect replaces it.
        OkHttpCallCompletionCoordinator.setNanoTimeSource { TimeUnit.SECONDS.toNanos(301) }
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        assertThat(OkHttpCallCompletionCoordinator.abandonIfCurrent(call, firstSpan)).isFalse()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)
        verify(exactly = 0) { instrumenter.end(Context.root(), chain, any(), any()) }
    }

    @Test
    fun `a throwing enricher still ends the span`() {
        val throwingEnricher = mockk<OkHttpTimingSpanEnricher>()
        every { throwingEnricher.enrich(any(), any()) } throws RuntimeException("boom")
        OkHttpCallCompletionCoordinator.configure(instrumenter, throwingEnricher)
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        OkHttpCallCompletionCoordinator.onCallEnd(call)

        verify { instrumenter.end(Context.root(), chain, null, null) }
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()
    }

    @Test
    fun `re-registering does not discard timing recorded before the first attempt`() {
        // callStart fires before the network interceptor runs, so timing state already exists on
        // the very first registration. Routing that through the untraced-completion path would
        // discard it and strip every http.client.timing.* attribute from the call.
        val state = OkHttpCallTimingStore.stateFor(call)
        state.callStartNanos = 0L
        state.responseHeadersStartNanos = 10_000_000L
        state.responseBodyEndNanos = 30_000_000L

        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)
        OkHttpCallCompletionCoordinator.onCallEnd(call)

        verify { span.setAttribute(OkHttpTimingAttributes.DOWNLOAD_MS, 20L) }
    }

    @Test
    fun `onCallFailed ends traced span with error`() {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L
        val error = IOException("boom")
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        OkHttpCallCompletionCoordinator.onCallFailed(call, error)

        verify { instrumenter.end(Context.root(), chain, null, error) }
        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, false) }
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }
}
