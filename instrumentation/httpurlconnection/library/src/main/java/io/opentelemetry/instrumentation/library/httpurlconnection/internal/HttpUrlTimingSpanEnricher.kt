/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import java.net.URLConnection

internal object HttpUrlTimingSpanEnricher {
    fun enrich(
        span: Span,
        connection: URLConnection,
    ) {
        val totalMs = HttpUrlConnectionTiming.removeAndFinalize(connection) ?: return
        span.setAttribute(HttpUrlConnectionTimingAttributes.TOTAL_MS, totalMs)
        span.setAttribute(HttpUrlConnectionTimingAttributes.PHASES_SUPPORTED, false)
        span.addEvent(
            HttpUrlConnectionTimingAttributes.EVENT_CALL,
            Attributes.of(HttpUrlConnectionTimingAttributes.DURATION_MS, totalMs),
        )
        RumDiagnostics.d { "httpUrlConnection: timing total_ms=$totalMs" }
    }
}
