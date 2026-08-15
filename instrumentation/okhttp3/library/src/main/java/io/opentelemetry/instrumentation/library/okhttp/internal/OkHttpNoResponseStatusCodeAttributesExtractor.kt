/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.semconv.HttpAttributes
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Reports `http.response.status_code = 0` for calls that failed before any response arrived.
 *
 * The upstream HTTP attributes extractor only emits the status code when it is greater than zero,
 * so a failed call otherwise carries no status at all and is indistinguishable downstream from a
 * request that was never instrumented. This extractor is registered after that one and fills the
 * gap for client-side failures (DNS, connection refused, TLS), leaving timeouts absent — see
 * [HttpErrorCategory.reportsZeroStatusCode].
 *
 * Note this deviates from the OpenTelemetry semantic conventions, which leave the attribute unset
 * when no response was received. It is intentional: the ingest contract distinguishes "reached the
 * server" from "never got there" by the presence of a zero status.
 */
internal object OkHttpNoResponseStatusCodeAttributesExtractor :
    AttributesExtractor<Interceptor.Chain, Response> {
    private const val NO_RESPONSE_STATUS_CODE = 0L

    override fun onStart(
        attributes: AttributesBuilder,
        parentContext: Context,
        request: Interceptor.Chain,
    ) {
        // no-op
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: Interceptor.Chain,
        response: Response?,
        error: Throwable?,
    ) {
        // A response means the server answered; its real status is already recorded upstream.
        if (response != null) return
        if (!HttpErrorCategory.reportsZeroStatusCode(error)) return
        attributes.put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, NO_RESPONSE_STATUS_CODE)
    }
}
