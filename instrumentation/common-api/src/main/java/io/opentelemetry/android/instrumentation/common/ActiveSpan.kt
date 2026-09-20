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

    fun spanInProgress(): Boolean = span != null

    /**
     * The span currently held, or `null`.
     *
     * Exposed so a caller that defers [endActiveSpan] to a later callback can check the span it
     * meant to end is still the active one. Between the two points this holder may have been
     * emptied and refilled -- a lifecycle callback ending the span and the next one starting
     * another -- and ending then would close an unrelated span.
     */
    fun currentSpan(): Span? = span

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
     * Pops the span off the current thread's context while leaving it open in this holder.
     *
     * [startSpan] makes the span current, and [endActiveSpan] is what normally closes that scope.
     * A caller that defers the end to a later callback would otherwise keep the span current for
     * the whole wait, so anything started on this thread in between would be parented to it. This
     * lets the end be deferred without extending how long the span stays current.
     */
    fun closeScopeOnly() {
        scope?.let {
            it.close()
            scope = null
        }
    }

    fun endActiveSpan() {
        scope?.let {
            it.close()
            scope = null
        }
        span?.let {
            it.end()
            span = null
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
