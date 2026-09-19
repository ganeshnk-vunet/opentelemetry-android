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
    private val lock = Any()
    private var activeSpan: Span? = null
    private var rootContext: Context? = null
    private var generation: Long = 0

    /** Starts a new interaction rooted at [root] (for example `ui.interaction`). Clears any stale interaction. */
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

    /**
     * When the interaction began, as epoch nanoseconds on the SDK clock, or `null` if there is no
     * live interaction or its root span does not carry a readable start time.
     *
     * Read from the root span rather than stored separately so the value comes from the same clock
     * that stamped the span, with no second time source to drift out of the domain other telemetry
     * is recorded in. [activate] replaces the *active* span as navigation proceeds, but never
     * [rootContext], so this stays anchored to the originating interaction rather than sliding
     * forward to the most recent navigation.
     *
     * Returns `null` for a non-SDK span (a no-op tracer, or a propagated remote parent), which is
     * why every caller must treat an unknown start time as "do not report" rather than zero.
     */
    fun rootStartedAtNanos(): Long? {
        val root = synchronized(lock) { rootContext } ?: return null
        val span = Span.fromContext(root)
        return (span as? ReadableSpan)?.toSpanData()?.startEpochNanos
    }

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
