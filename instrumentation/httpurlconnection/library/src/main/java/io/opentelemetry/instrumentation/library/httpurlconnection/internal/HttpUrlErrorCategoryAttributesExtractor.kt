/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import java.net.URLConnection

internal object HttpUrlErrorCategoryAttributesExtractor :
    AttributesExtractor<URLConnection, Int> {
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
        val category =
            HttpErrorCategory.fromThrowable(error)
                ?: response?.let { HttpErrorCategory.fromStatusCode(it) }
                ?: return
        attributes.put(HttpErrorCategory.ATTRIBUTE_KEY, category)
    }
}
