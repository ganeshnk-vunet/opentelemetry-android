/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.common

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope

class ActiveSpan(
    private val lastVisibleScreen: () -> String?,
) {
    private var span: Span? = null
    private var scope: Scope? = null

    /** Open, no longer in the active slot, waiting on the callback that will end it. */
    private var deferredSpan: Span? = null

    fun spanInProgress(): Boolean = span != null

    // it's fine to not close the scope here, will be closed in endActiveSpan()
    fun startSpan(spanCreator: () -> Span) {
        // don't start one if there's already one in progress
        if (span != null) {
            return
        }
        span = spanCreator()
        scope = span?.makeCurrent()
    }

    /**
     * Hands the open span over to a caller that will end it from a later callback, clearing both
     * the current thread's context and the active slot. Returns the span, or `null` if none.
     *
     * Two reasons the span has to leave the active slot rather than simply stay open in it:
     *
     * 1. [startSpan] makes the span current and [endActiveSpan] is what normally closes that
     *    scope, so a deferred end would keep it current for the whole wait and parent anything
     *    started on this thread in between to it.
     * 2. [spanInProgress] gates `startSpanIfNoneInProgress`, so leaving it in the active slot
     *    would swallow the next lifecycle span and misfile its events onto this one.
     *
     * The span is still ended by [endActiveSpan] if the callback never arrives, so a lifecycle
     * end closes it rather than leaving it open for a frame that will never be drawn.
     */
    fun deferEnd(): Span? {
        closeScope()
        deferredSpan = span
        span = null
        return deferredSpan
    }

    /**
     * Ends [expected], running [beforeEnd] on it first, but only if it is still the span handed
     * out by [deferEnd]. A no-op otherwise: between the two points a lifecycle callback may have
     * ended it already, and ending then would close whatever span has started since.
     */
    fun endDeferred(
        expected: Span,
        beforeEnd: (Span) -> Unit,
    ) {
        if (deferredSpan !== expected) {
            return
        }
        deferredSpan = null
        beforeEnd(expected)
        expected.end()
    }

    fun endActiveSpan() {
        closeScope()
        span?.let {
            it.end()
            span = null
        }
        // A span whose end was deferred to a callback that never came must not outlive the
        // lifecycle. Ending it here is correct, and correctly without whatever the callback
        // would have recorded, because the thing it was waiting for never happened.
        deferredSpan?.let {
            it.end()
            deferredSpan = null
        }
    }

    private fun closeScope() {
        scope?.let {
            it.close()
            scope = null
        }
    }

    fun addEvent(eventName: String) {
        span?.addEvent(eventName)
    }

    fun addPreviousScreenAttribute(screenName: String) {
        span?.let {
            val previouslyVisibleScreen = lastVisibleScreen()
            if (previouslyVisibleScreen != null && screenName != previouslyVisibleScreen) {
                it.setAttribute(RumConstants.LAST_SCREEN_NAME_KEY, previouslyVisibleScreen)
            }
        }
    }
}
