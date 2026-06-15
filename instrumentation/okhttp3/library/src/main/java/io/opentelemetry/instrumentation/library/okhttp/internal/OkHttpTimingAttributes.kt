/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.api.common.AttributeKey

/**
 * Incubating HTTP client timing attribute and event names for OkHttp instrumentation.
 */
internal object OkHttpTimingAttributes {
    val DURATION_MS: AttributeKey<Long> = AttributeKey.longKey("duration_ms")

    const val DNS_MS = "http.client.timing.dns_ms"
    const val CONNECT_MS = "http.client.timing.connect_ms"
    const val TLS_MS = "http.client.timing.tls_ms"
    const val TTFB_MS = "http.client.timing.ttfb_ms"
    const val DOWNLOAD_MS = "http.client.timing.download_ms"
    const val TOTAL_MS = "http.client.timing.total_ms"
    const val POOL_WAIT_MS = "http.client.timing.pool_wait_ms"
    const val REQUEST_HEADERS_MS = "http.client.timing.request_headers_ms"
    const val REQUEST_BODY_MS = "http.client.timing.request_body_ms"
    const val RESPONSE_HEADERS_MS = "http.client.timing.response_headers_ms"
    const val PROXY_SELECT_MS = "http.client.timing.proxy_select_ms"
    const val PHASES_COMPLETE = "http.client.timing.phases_complete"

    const val EVENT_DNS = "http.dns"
    const val EVENT_CONNECT = "http.connect"
    const val EVENT_SECURE_CONNECT = "http.secure_connect"
    const val EVENT_TTFB = "http.ttfb"
    const val EVENT_DOWNLOAD = "http.download"
    const val EVENT_CALL = "http.call"
    const val EVENT_POOL_WAIT = "http.pool_wait"
    const val EVENT_REQUEST_HEADERS = "http.request_headers"
    const val EVENT_REQUEST_BODY = "http.request_body"
    const val EVENT_RESPONSE_HEADERS = "http.response_headers"
    const val EVENT_PROXY_SELECT = "http.proxy_select"
}

internal data class OkHttpTimingResult(
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val ttfbMs: Long? = null,
    val downloadMs: Long? = null,
    val totalMs: Long? = null,
    val poolWaitMs: Long? = null,
    val requestHeadersMs: Long? = null,
    val requestBodyMs: Long? = null,
    val responseHeadersMs: Long? = null,
    val proxySelectMs: Long? = null,
    val phasesComplete: Boolean = true,
)
