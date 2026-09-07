/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.UrlAttributes
import java.net.URL
import java.net.URLConnection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HttpUrlUrlPartsAttributesExtractorTest {
    private fun attributesFor(url: String?): Attributes {
        val connection = mockk<URLConnection>(relaxed = true)
        every { connection.url } returns url?.let { URL(it) }
        val attributes = Attributes.builder()
        HttpUrlUrlPartsAttributesExtractor.onStart(attributes, Context.root(), connection)
        return attributes.build()
    }

    @Test
    fun splitsAFullUrl() {
        val attributes = attributesFor("https://example.com:8443/vuepay/account/list?page=2&size=50")
        assertThat(attributes.get(UrlAttributes.URL_SCHEME)).isEqualTo("https")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/vuepay/account/list")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("page=2&size=50")
    }

    @Test
    fun omitsQueryWhenTheUrlHasNone() {
        val attributes = attributesFor("https://example.com/account/list")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isNull()
    }

    @Test
    fun reportsRootPathForAHostOnlyUrl() {
        // java.net.URL returns "" here, unlike okhttp's "/", so the shared helper's fallback is
        // what keeps the two instrumentations agreeing on this case.
        val attributes = attributesFor("http://example.com")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/")
    }

    @Test
    fun writesNothingWhenTheConnectionHasNoUrl() {
        assertThat(attributesFor(null).isEmpty).isTrue()
    }

    @Test
    fun keepsTheEncodedFormSoPartsMatchUrlFull() {
        val attributes = attributesFor("https://example.com/search/a%20b?q=a%20b")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/search/a%20b")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("q=a%20b")
    }
}
