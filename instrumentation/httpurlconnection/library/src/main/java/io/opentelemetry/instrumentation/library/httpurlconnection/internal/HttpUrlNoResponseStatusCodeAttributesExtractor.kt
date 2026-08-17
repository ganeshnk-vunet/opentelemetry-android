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
 * **A non-positive response code does not mean "no response".** A throwable does not imply the
 * server stayed silent: on Android `HttpURLConnection.getInputStream()` raises
 * `FileNotFoundException` for any response `>= 400`, and a body read can fail long after a 200.
 * `HttpUrlReplacements.reportWithThrowable` recovers the code the connection already holds, so
 * those cases normally arrive here with the real status (`404`, `500`, `200`) and exit on the
 * `response > 0` branch.
 *
 * The `-1` sentinel therefore means "no code available", which is *not* the same as "no response
 * received" — it also covers the connection failing again while the code is being retrieved. The
 * throwable is the only signal that separates the two, so
 * [HttpErrorCategory.reportsZeroStatusCode] matches pre-request failure types exactly rather than
 * treating any transport error as proof the server was never reached.
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
