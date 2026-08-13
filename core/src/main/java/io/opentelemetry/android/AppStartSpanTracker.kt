/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Publishes the in-flight `app.start` span to [AppStartSpans] so wrapper SDKs can
 * stamp their own startup phases onto it.
 *
 * Implemented as a span processor rather than wired into a specific instrumentation
 * because `app.start` is created from more than one place: `AppStartupTimer` opens
 * the cold-start span, while `ActivityTracer` opens the warm and hot ones. A
 * processor sees all three, so returning from the background behaves the same as a
 * fresh launch without either class knowing this exists.
 *
 * [AppStartSpans.publish] refuses to replace a still-recording span, so a warm
 * start that opens while cold is waiting on the first frame does not steal the
 * holder from wrappers.
 */
internal class AppStartSpanTracker : SpanProcessor {
    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        if (span.name == RumConstants.APP_START_SPAN_NAME) {
            AppStartSpans.publish(span)
        }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        // Deliberately no name check: only an app.start span is ever published, so a
        // span-context match already implies the name matched. ReadableSpan.getName()
        // is synchronized on the span's monitor, and this runs for every span the app
        // emits — getSpanContext() reads a final field and does not lock.
        AppStartSpans.clearIfTracked(span.spanContext)
    }

    override fun isEndRequired(): Boolean = true
}
