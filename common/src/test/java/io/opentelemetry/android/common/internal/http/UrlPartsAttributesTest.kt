/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.semconv.UrlAttributes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class UrlPartsAttributesTest {
    private fun put(
        scheme: String?,
        path: String?,
        query: String?,
    ): Attributes {
        val builder = Attributes.builder()
        UrlPartsAttributes.putUrlParts(builder, scheme, path, query)
        return builder.build()
    }

    @Test
    fun writesAllThreeWhenPresent() {
        val attributes = put("https", "/vuepay/account/list", "page=2&size=50")
        assertThat(attributes.get(UrlAttributes.URL_SCHEME)).isEqualTo("https")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/vuepay/account/list")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("page=2&size=50")
    }

    @Test
    fun omitsQueryEntirelyWhenAbsent() {
        // Not "" — semconv makes url.query conditionally required, so an empty string would
        // assert a blank query was present, a different claim from there being none.
        assertThat(put("https", "/a", null).get(UrlAttributes.URL_QUERY)).isNull()
    }

    @Test
    fun omitsQueryWhenBlank() {
        assertThat(put("https", "/a", "").get(UrlAttributes.URL_QUERY)).isNull()
        assertThat(put("https", "/a", "   ").get(UrlAttributes.URL_QUERY)).isNull()
    }

    @Test
    fun fallsBackToRootPathWhenUrlHasNone() {
        // A request for the root resource has a path and it is "/"; emitting nothing would make
        // the root indistinguishable from a URL that could not be parsed.
        assertThat(put("https", null, null).get(UrlAttributes.URL_PATH)).isEqualTo("/")
        assertThat(put("https", "", null).get(UrlAttributes.URL_PATH)).isEqualTo("/")
    }

    @Test
    fun omitsSchemeWhenBlank() {
        assertThat(put(null, "/a", null).get(UrlAttributes.URL_SCHEME)).isNull()
        assertThat(put("", "/a", null).get(UrlAttributes.URL_SCHEME)).isNull()
    }

    @Test
    fun keepsPercentEncodingSoPartsMatchUrlFull() {
        // url.full is built from the encoded URL. Decoding here would produce parts that do not
        // add up to the whole sitting next to them on the same span.
        val attributes = put("https", "/search/a%20b", "q=a%20b&r=%2F")
        assertThat(attributes.get(UrlAttributes.URL_PATH)).isEqualTo("/search/a%20b")
        assertThat(attributes.get(UrlAttributes.URL_QUERY)).isEqualTo("q=a%20b&r=%2F")
    }
}
