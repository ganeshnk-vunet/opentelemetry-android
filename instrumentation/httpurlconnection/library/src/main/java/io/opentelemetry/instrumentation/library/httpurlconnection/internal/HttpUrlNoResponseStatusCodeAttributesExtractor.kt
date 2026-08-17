/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.semconv.HttpAttributes
import java.net.URLConnection

/**
 * Reports `http.response.status_code = 0` for requests that failed before any response arrived.
 *
 * The upstream HTTP attributes extractor only emits the status code when it is greater than zero,
 * so a failed request otherwise carries no status at all and is indistinguishable downstream from a
 * request that was never instrumented. This extractor is registered after that one and fills the
 * gap for client-side failures (DNS, connection refused, TLS), leaving timeouts absent — see
 * [HttpErrorCategory.reportsZeroStatusCode].
 *
 * **The response code alone cannot be trusted to mean "no response".** `HttpUrlReplacements
 * .reportWithThrowable` passes `-1` on *every* throwable path, including ones where the server
 * demonstrably answered: on Android `HttpURLConnection.getInputStream()` raises
 * `FileNotFoundException` for any response `>= 400`, so a plain 404 arrives here as `(-1,
 * FileNotFoundException)`. A mid-body read failure after a 200 and a request-body write failure
 * arrive the same way. The `-1` therefore means "no code available", not "no response received",
 * and the throwable is the only signal that separates the two —
 * [HttpErrorCategory.reportsZeroStatusCode] matches pre-request failure types exactly so those
 * cases stay absent rather than being reported as zero.
 *
 * Note this deviates from the OpenTelemetry semantic conventions, which leave the attribute unset
 * when no response was received. It is intentional: the ingest contract distinguishes "reached the
 * server" from "never got there" by the presence of a zero status.
 */
internal object HttpUrlNoResponseStatusCodeAttributesExtractor :
    AttributesExtractor<URLConnection, Int> {
    private const val NO_RESPONSE_STATUS_CODE = 0L

    override fun onStart(
        attributes: AttributesBuilder,
        parentContext: Context,
        request: URLConnection,
    ) {
        // no-op
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: URLConnection,
        response: Int?,
        error: Throwable?,
    ) {
        // A positive code means the server answered; its real status is already recorded upstream.
        // A non-positive one proves nothing on its own (see the class KDoc), so the throwable
        // decides.
        if (response != null && response > 0) return
        if (!HttpErrorCategory.reportsZeroStatusCode(error)) return
        attributes.put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, NO_RESPONSE_STATUS_CODE)
    }
}
