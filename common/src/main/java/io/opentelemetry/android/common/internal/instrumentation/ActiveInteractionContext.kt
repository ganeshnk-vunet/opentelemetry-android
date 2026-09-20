/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadableSpan

/**
 * Holds the active OpenTelemetry context for a single user interaction (for example a navigation
 * span after screen transition) so downstream async work can parent correctly. Cleared when a new
 * click interaction starts or instrumentation uninstalls.
 */
object ActiveInteractionContext {
    /**
     * How long after the first claim the interaction start is still served to another caller.
     *
     * Sized for a second *collector* reporting the *same* navigation, which lands within a frame
     * or two of the first, not for a second navigation. Deliberately short: everything past it is
     * treated as a new event that the tap no longer explains.
     *
     * A navigation chain that steps again inside this window is still timed from the tap. That is
     * left alone on purpose — when the next step follows that fast, the user really did wait from
     * the tap, so the number is not wrong. What the window has to exclude is idle time, which is
     * orders of magnitude larger than this.
     */
    internal const val CONCURRENT_COLLECTOR_GRACE_NANOS: Long = 250_000_000L

    private val lock = Any()
    private var activeSpan: Span? = null
    private var rootContext: Context? = null
    private var generation: Long = 0

    /**
     * Start of the most recent interaction, kept deliberately **outside** the parenting window.
     *
     * [rootContext] is cleared when the interaction window expires, because parenting a span to a
     * long-finished tap would be wrong. Timing is the opposite case: the slower a navigation is,
     * the longer after the tap it commits, and the more worth measuring it is. Reading the start
     * time through the parenting window therefore lost exactly the navigations worth investigating
     * — anything slower than the window reported no duration at all rather than a large one.
     *
     * Survives [end]; cleared only by [clear] or replaced by the next [begin].
     */
    private var lastInteractionStartedAtNanos: Long? = null

    /**
     * When [lastInteractionStartedAtNanos] was first handed out, or `null` while it is unclaimed.
     *
     * A tap explains the navigation it causes, and nothing after that. Without this marker the
     * stored start time stayed readable for the full attribution limit, so a screen the app opened
     * by itself — a session-expiry redirect, a timer, a deep link — was timed from whatever the
     * user last tapped. Tap, sit for twenty seconds, get redirected, and the redirect reported a
     * twenty-second wait nobody experienced.
     */
    private var lastInteractionConsumedAtNanos: Long? = null

    /** Starts a new interaction rooted at [root] (for example `ui.interaction`). Clears any stale interaction. */
    fun begin(root: Span): Long =
        synchronized(lock) {
            activeSpan = root
            rootContext = Context.current().with(root)
            lastInteractionStartedAtNanos = startEpochNanosOf(root)
            lastInteractionConsumedAtNanos = null
            ++generation
        }

    /** Replaces the active parent within the current interaction (for example `ui.navigation`). */
    fun activate(span: Span) {
        synchronized(lock) {
            activeSpan = span
        }
    }

    /** Parent context for spans created explicitly within the current interaction (for example nav under click). */
    fun rootContext(): Context? = synchronized(lock) { rootContext }

    /**
     * Start of the most recent interaction regardless of whether its parenting window is still
     * open, for callers that need to measure elapsed time rather than establish a parent, or
     * `null` once that interaction has already been claimed.
     *
     * **Claimed by the first caller**, because a tap explains the navigation it causes and nothing
     * afterwards. [nowNanos] is the caller's own event time on the SDK clock — the moment being
     * timed, not the moment of the call — which keeps this object free of a clock of its own and
     * in the same time domain as the span being stamped.
     *
     * The claim is not exclusive immediately. Two navigation collectors can be active in one
     * process — a Compose host that also runs the View collector emits a `ui.navigation` span from
     * each — and a hard claim would give the first emitter a duration and the second none for the
     * very same navigation. Both are still served for [CONCURRENT_COLLECTOR_GRACE_NANOS] after the
     * first claim, after which the interaction is spent and this returns `null`.
     *
     * Callers still bound staleness with their own limit: this says the interaction has not been
     * used yet, not that it is recent enough to explain the caller's event.
     */
    fun lastInteractionStartedAtNanos(nowNanos: Long): Long? =
        synchronized(lock) {
            val startedAtNanos = lastInteractionStartedAtNanos ?: return@synchronized null
            val consumedAtNanos = lastInteractionConsumedAtNanos
            when {
                consumedAtNanos == null -> {
                    lastInteractionConsumedAtNanos = nowNanos
                    startedAtNanos
                }

                nowNanos - consumedAtNanos <= CONCURRENT_COLLECTOR_GRACE_NANOS -> startedAtNanos
                else -> null
            }
        }

    private fun startEpochNanosOf(span: Span): Long? =
        (span as? ReadableSpan)?.toSpanData()?.startEpochNanos

    /** Ends the interaction identified by [token] only if it is still current (guards rapid taps). */
    fun end(token: Long) {
        synchronized(lock) {
            if (token == generation) {
                clearLocked()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            clearLocked()
            // Only a full clear (uninstall, or a test tearing down) drops the interaction start;
            // end() deliberately leaves it so a slow navigation can still be timed.
            lastInteractionStartedAtNanos = null
            lastInteractionConsumedAtNanos = null
        }
    }

    private fun clearLocked() {
        activeSpan = null
        rootContext = null
    }

    /**
     * Resolves the parent context for an HTTP span.
     * If the current context is marked as an exporter context, returns the current context.
     * If there is an active interaction span and the current context has no valid span, parents to the active span.
     * If the current context has a valid span, only overrides it if it belongs to the same trace.
     */
    @JvmStatic
    fun parentContextOr(current: Context): Context {
        if (ExporterMarker.isExporterContext(current)) {
            return current
        }
        val active = synchronized(lock) { activeSpan } ?: return current
        val currentSpan = Span.fromContext(current)
        val activeSpanContext = active.spanContext
        if (!activeSpanContext.isValid) {
            return current
        }
        val currentSpanContext = currentSpan.spanContext
        if (!currentSpanContext.isValid) {
            return current.with(active)
        }
        if (currentSpanContext.traceId == activeSpanContext.traceId) {
            return current.with(active)
        }
        return current
    }
}
