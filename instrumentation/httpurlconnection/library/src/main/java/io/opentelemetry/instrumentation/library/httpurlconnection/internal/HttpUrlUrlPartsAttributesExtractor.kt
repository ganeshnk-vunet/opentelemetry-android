/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.opentelemetry.android.common.internal.http.UrlPartsAttributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import java.net.URLConnection

/**
 * The [OkHttpUrlPartsAttributesExtractor][io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpUrlPartsAttributesExtractor]
 * counterpart for `HttpURLConnection`: adds `url.scheme`, `url.path` and `url.query`, which the
 * built-in HTTP client attributes extractor does not emit. See [UrlPartsAttributes] for why.
 *
 * `java.net.URL` already stores the raw, still-encoded components — `getPath()` and `getQuery()`
 * return them exactly as they appeared in the URL — so these line up with `url.full` without any
 * further work, the same way the okhttp extractor uses okhttp's encoded accessors.
 */
internal object HttpUrlUrlPartsAttributesExtractor : AttributesExtractor<URLConnection, Int> {
    override fun onStart(
        attributes: AttributesBuilder,
        parentContext: Context,
        request: URLConnection,
    ) {
        val url = request.url ?: return
        UrlPartsAttributes.putUrlParts(
            attributes,
            url.protocol,
            url.path,
            url.query,
        )
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: URLConnection,
        response: Int?,
        error: Throwable?,
    ) {
        // no-op — everything is available at start.
    }
}
