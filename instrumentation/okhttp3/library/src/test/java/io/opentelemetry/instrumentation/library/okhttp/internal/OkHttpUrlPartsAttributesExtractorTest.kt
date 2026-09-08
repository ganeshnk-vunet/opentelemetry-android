/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.UrlAttributes
import okhttp3.Interceptor
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OkHttpUrlPartsAttributesExtractorTest {
    private fun attributesFor(url: String): Attributes {
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns Request.Builder().url(url).build()
        val attributes = Attributes.builder()
        OkHttpUrlPartsAttributesExtractor.onStart(attributes, Context.root(), chain)
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
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/account/list")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isNull()
    }

    @Test
    fun reportsRootPathForAHostOnlyUrl() {
        val attributes = attributesFor("http://example.com")
        assertThat(attributes.get(UrlAttributes.URL_SCHEME)).isEqualTo("http")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/")
    }

    @Test
    fun keepsTheEncodedFormSoPartsMatchUrlFull() {
        val attributes = attributesFor("https://example.com/search/a%20b?q=a%20b")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/search/a%20b")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("q=a%20b")
    }

    @Test
    fun doesNotWriteHostOrPortIntoTheParts() {
        // server.address / server.port are the built-in extractor's job; duplicating them here
        // would double-write keys another extractor owns.
        val attributes = attributesFor("https://example.com:8443/a").asMap().keys.map { it.key }
        assertThat(attributes).containsExactlyInAnyOrder("url.scheme", "url.path")
    }

    /**
     * `+` means a space in a query string. `encodedQuery` preserves it; the decoded accessor would
     * hand back `q=a b`, which no longer matches the `url.full` sitting beside it. Locks the
     * encoded accessor against a well-meaning switch to `url.query`.
     */
    @Test
    fun keepsPlusRatherThanDecodingItToASpace() {
        val attributes = attributesFor("https://example.com/search?q=a+b")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("q=a+b")
    }

    /**
     * `%20` survives decoding as a space and leaves the path shape intact; `%2F` does not — decoded
     * it becomes a separator and changes what the path means. Worth locking separately.
     */
    @Test
    fun keepsEncodedSlashesInPathAndQuery() {
        val attributes = attributesFor("https://example.com/a%2Fb?p=x%2Fy")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/a%2Fb")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("p=x%2Fy")
    }

    /** The redaction that upstream applies to `url.full` must reach `url.query` through here too. */
    @Test
    fun redactsSensitiveQueryParameters() {
        val attributes = attributesFor("https://example.com/o?AWSAccessKeyId=AKIA&Signature=deadbeef")
        assertThat(attributes.get(UrlAttributes.URL_QUERY))
            .isEqualTo("AWSAccessKeyId=REDACTED&Signature=REDACTED")
    }
}
