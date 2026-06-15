/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import okhttp3.Interceptor
import okhttp3.Response

internal object OkHttpErrorCategoryAttributesExtractor :
    AttributesExtractor<Interceptor.Chain, Response> {
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
        val category =
            HttpErrorCategory.fromThrowable(error)
                ?: response?.let { HttpErrorCategory.fromStatusCode(it.code) }
                ?: return
        attributes.put(HttpErrorCategory.ATTRIBUTE_KEY, category)
    }
}
