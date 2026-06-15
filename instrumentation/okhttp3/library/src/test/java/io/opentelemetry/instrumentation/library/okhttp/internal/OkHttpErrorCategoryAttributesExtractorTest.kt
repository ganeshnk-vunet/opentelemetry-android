/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.internal.http.HttpErrorCategory
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OkHttpErrorCategoryAttributesExtractorTest {
    private val chain = mockk<Interceptor.Chain>(relaxed = true)

    @Test
    fun setsTimeoutCategoryFromThrowable() {
        val attributes = Attributes.builder()
        OkHttpErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            null,
            SocketTimeoutException(),
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isEqualTo(HttpErrorCategory.TIMEOUT)
    }

    @Test
    fun setsDnsCategoryFromThrowable() {
        val attributes = Attributes.builder()
        OkHttpErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            null,
            UnknownHostException(),
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isEqualTo(HttpErrorCategory.DNS)
    }

    @Test
    fun setsHttpClientCategoryFromStatusCode() {
        val response = mockk<Response>()
        every { response.code } returns 503
        val attributes = Attributes.builder()
        OkHttpErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            response,
            null,
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY))
            .isEqualTo(HttpErrorCategory.HTTP_CLIENT)
    }

    @Test
    fun omitsCategoryForSuccessfulResponse() {
        val response = mockk<Response>()
        every { response.code } returns 200
        val attributes = Attributes.builder()
        OkHttpErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            response,
            null,
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isNull()
    }

    @Test
    fun prefersThrowableCategoryOverStatusCode() {
        val response = mockk<Response>()
        every { response.code } returns 500
        val attributes = Attributes.builder()
        OkHttpErrorCategoryAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            response,
            UnknownHostException(),
        )
        assertThat(attributes.build().get(HttpErrorCategory.ATTRIBUTE_KEY)).isEqualTo(HttpErrorCategory.DNS)
    }
}
