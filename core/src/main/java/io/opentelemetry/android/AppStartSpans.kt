/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.trace.ReadWriteSpan
import java.util.concurrent.atomic.AtomicReference

/**
 * The `app.start` span that is currently in flight, or null when no app start is in
 * progress.
 *
 * This exists so first-party wrappers (Flutter, React Native) can stamp their own
 * startup phases onto the span the native SDK owns, instead of emitting a parallel
 * startup trace that consumers would have to join. The wrapper's phases happen
 * inside the startup window but are invisible to the native layer, which cannot see
 * past the engine handoff.
 *
 * The write path is deliberately not public. A publicly settable global would let
 * any library on the classpath redirect wrapper telemetry onto a span of its
 * choosing, or clear it and drop the telemetry silently. The value is maintained by
 * [AppStartSpanTracker], which observes every `app.start` span the SDK creates —
 * cold, warm and hot alike, regardless of which instrumentation started it.
 *
 * A still-recording span is not replaced. Cold start stays open until the first
 * committed frame, and a warm/hot `app.start` can open in that window (config
 * change recreating the first activity). Overwriting would point wrappers at the
 * short warm span; when it ended they would see null while cold was still live.
 * Warm/hot publish after the previous span has ended or aged out.
 *
 * Callers must treat null as "startup window closed" and drop the event. Buffering
 * and replaying it later would attach a phase to a span that has already been
 * exported, or to the next startup's span.
 *
 * Not populated when the host app supplies its own pre-built `OpenTelemetrySdk` via
 * `SdkPreconfiguredRumBuilder`: a span processor cannot be added to a tracer
 * provider that is already built, so wrapper startup phases are unavailable on that
 * path.
 */
object AppStartSpans {
    private val tracked = AtomicReference<ReadWriteSpan?>()

    /**
     * The live `app.start` span, or null when the startup window is closed.
     *
     * Returns null for a span that has already ended — the tracker is notified after
     * the fact, so without this a caller could observe a closed span and write into
     * it — and for one that has been open beyond
     * [RumConstants.APP_START_MAX_WINDOW_NANOS]. Stale references are dropped so an
     * abandoned span (never ended) does not pin the `ReadWriteSpan` for the rest of
     * the process.
     */
    @JvmStatic
    val current: Span?
        get() {
            val span = tracked.get() ?: return null
            if (!span.isTrusted()) {
                tracked.compareAndSet(span, null)
                return null
            }
            return span
        }

    internal fun publish(span: ReadWriteSpan) {
        if (!span.isTrusted()) {
            return
        }
        while (true) {
            val existing = tracked.get()
            if (existing != null && existing.isTrusted()) {
                return
            }
            if (tracked.compareAndSet(existing, span)) {
                return
            }
        }
    }

    /**
     * Clears only when [spanContext] identifies the tracked span, so a span ending
     * after a newer one has been published does not blind wrappers for the rest of
     * the newer span's window.
     */
    internal fun clearIfTracked(spanContext: SpanContext) {
        val existing = tracked.get() ?: return
        if (existing.spanContext == spanContext) {
            tracked.compareAndSet(existing, null)
        }
    }

    internal fun clear() {
        tracked.set(null)
    }

    private fun ReadWriteSpan.isTrusted(): Boolean =
        !hasEnded() && latencyNanos <= RumConstants.APP_START_MAX_WINDOW_NANOS
}
