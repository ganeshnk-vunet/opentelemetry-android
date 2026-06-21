/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context

/**
 * Holds the active OpenTelemetry context for a single user interaction (for example a navigation
 * span after screen transition) so downstream async work can parent correctly. Cleared when a new
 * click interaction starts or instrumentation uninstalls.
 */
object ActiveInteractionContext {
    private val lock = Any()
    private var activeSpan: Span? = null
    private var rootContext: Context? = null
    private var generation: Long = 0

    /** Starts a new interaction rooted at [root] (for example `ui.click`). Clears any stale interaction. */
    fun begin(root: Span): Long =
        synchronized(lock) {
            activeSpan = root
            rootContext = Context.current().with(root)
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
