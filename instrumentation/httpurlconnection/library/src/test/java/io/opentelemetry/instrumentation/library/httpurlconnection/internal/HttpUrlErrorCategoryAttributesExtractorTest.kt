/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.mockk.mockk
import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import java.io.IOException
import java.net.URLConnection
import java.net.UnknownHostException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HttpUrlErrorCategoryAttributesExtractorTest {
    private val connection = mockk<URLConnection>(relaxed = true)

    @Test
    fun setsIoCategoryFromThrowable() {
        val attributes = Attributes.builder()
        HttpUrlErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            connection,
            null,
            IOException("read failed"),
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isEqualTo(HttpErrorCategory.IO)
    }

    @Test
    fun setsDnsCategoryFromThrowable() {
        val attributes = Attributes.builder()
        HttpUrlErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            connection,
            null,
            UnknownHostException(),
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isEqualTo(HttpErrorCategory.DNS)
    }

    @Test
    fun setsHttpClientCategoryFromStatusCode() {
        val attributes = Attributes.builder()
        HttpUrlErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            connection,
            404,
            null,
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY))
            .isEqualTo(HttpErrorCategory.HTTP_CLIENT)
    }

    @Test
    fun omitsCategoryForSuccessfulStatusCode() {
        val attributes = Attributes.builder()
        HttpUrlErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            connection,
            200,
            null,
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isNull()
    }
}
