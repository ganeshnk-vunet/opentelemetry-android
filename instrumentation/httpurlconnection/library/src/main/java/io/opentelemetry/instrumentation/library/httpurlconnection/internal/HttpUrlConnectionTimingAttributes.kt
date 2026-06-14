/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.opentelemetry.api.common.AttributeKey

/**
 * Incubating HTTP client timing attribute and event names for HttpURLConnection instrumentation.
 */
internal object HttpUrlConnectionTimingAttributes {
    val DURATION_MS: AttributeKey<Long> = AttributeKey.longKey("duration_ms")

    const val TOTAL_MS = "http.client.timing.total_ms"
    const val PHASES_SUPPORTED = "http.client.timing.phases_supported"

    const val EVENT_CALL = "http.call"
}
