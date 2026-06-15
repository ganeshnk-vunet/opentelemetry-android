/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import okhttp3.Call

internal class OkHttpTimingSpanEnricher {
    fun enrich(
        span: Span,
        call: Call,
    ) {
        val timing = OkHttpCallTimingStore.remove(call) ?: return
        putTiming(span, timing.dnsMs, OkHttpTimingAttributes.DNS_MS, OkHttpTimingAttributes.EVENT_DNS)
        putTiming(span, timing.connectMs, OkHttpTimingAttributes.CONNECT_MS, OkHttpTimingAttributes.EVENT_CONNECT)
        putTiming(span, timing.tlsMs, OkHttpTimingAttributes.TLS_MS, OkHttpTimingAttributes.EVENT_SECURE_CONNECT)
        putTiming(span, timing.ttfbMs, OkHttpTimingAttributes.TTFB_MS, OkHttpTimingAttributes.EVENT_TTFB)
        putTiming(span, timing.downloadMs, OkHttpTimingAttributes.DOWNLOAD_MS, OkHttpTimingAttributes.EVENT_DOWNLOAD)
        putTiming(span, timing.totalMs, OkHttpTimingAttributes.TOTAL_MS, OkHttpTimingAttributes.EVENT_CALL)
        putTiming(span, timing.poolWaitMs, OkHttpTimingAttributes.POOL_WAIT_MS, OkHttpTimingAttributes.EVENT_POOL_WAIT)
        putTiming(
            span,
            timing.requestHeadersMs,
            OkHttpTimingAttributes.REQUEST_HEADERS_MS,
            OkHttpTimingAttributes.EVENT_REQUEST_HEADERS,
        )
        putTiming(
            span,
            timing.requestBodyMs,
            OkHttpTimingAttributes.REQUEST_BODY_MS,
            OkHttpTimingAttributes.EVENT_REQUEST_BODY,
        )
        putTiming(
            span,
            timing.responseHeadersMs,
            OkHttpTimingAttributes.RESPONSE_HEADERS_MS,
            OkHttpTimingAttributes.EVENT_RESPONSE_HEADERS,
        )
        putTiming(
            span,
            timing.proxySelectMs,
            OkHttpTimingAttributes.PROXY_SELECT_MS,
            OkHttpTimingAttributes.EVENT_PROXY_SELECT,
        )
        span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, timing.phasesComplete)
        RumDiagnostics.d {
            "okhttp: timing total_ms=${timing.totalMs} ttfb_ms=${timing.ttfbMs} dns_ms=${timing.dnsMs}"
        }
    }

    private fun putTiming(
        span: Span,
        durationMs: Long?,
        attributeKey: String,
        eventName: String,
    ) {
        if (durationMs == null) {
            return
        }
        span.setAttribute(attributeKey, durationMs)
        span.addEvent(
            eventName,
            Attributes.of(OkHttpTimingAttributes.DURATION_MS, durationMs),
        )
    }
}
