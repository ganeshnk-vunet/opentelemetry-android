/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.android.common.internal.http.UrlPartsAttributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `url.scheme`, `url.path` and `url.query`, which the built-in HTTP client attributes
 * extractor does not emit — it records `url.full` only. See [UrlPartsAttributes] for why these
 * are expected on a client span despite the per-part accessors living on the server-side getter.
 *
 * Written in [onStart] because every part is known from the request alone; nothing here depends
 * on the response, so the attributes are present even when the call fails before one arrives.
 *
 * The encoded forms are used deliberately. `url.full` is built from the encoded URL, so taking
 * the decoded path or query here would produce parts that do not add up to the whole they sit
 * next to on the same span.
 */
internal object OkHttpUrlPartsAttributesExtractor :
    AttributesExtractor<Interceptor.Chain, Response> {
    override fun onStart(
        attributes: AttributesBuilder,
        parentContext: Context,
        request: Interceptor.Chain,
    ) {
        val url = request.request().url
        UrlPartsAttributes.putUrlParts(
            attributes,
            url.scheme,
            url.encodedPath,
            url.encodedQuery,
        )
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: Interceptor.Chain,
        response: Response?,
        error: Throwable?,
    ) {
        // no-op — everything is available at start.
    }
}
