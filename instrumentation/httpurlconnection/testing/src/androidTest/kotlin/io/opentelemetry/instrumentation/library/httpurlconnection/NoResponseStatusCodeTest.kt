/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection

import io.opentelemetry.android.test.common.OpenTelemetryRumRule
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.instrumentation.library.httpurlconnection.HttpUrlConnectionTestUtil.executeGetReadingInputStreamOnly
import io.opentelemetry.sdk.trace.data.SpanData
import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end coverage for `http.response.status_code` on HttpURLConnection calls.
 *
 * The mocked extractor suite cannot catch the failure this guards against, because it is the
 * *instrumentation* — not the extractor — that decides what reaches `onEnd`.
 * `HttpUrlReplacements.reportWithThrowable` passes `-1` on every throwable path, and on Android
 * `HttpURLConnection.getInputStream()` raises `FileNotFoundException` for any response `>= 400`. So
 * a plain 404 read through `getInputStream()` reaches the extractor as `(-1,
 * FileNotFoundException)`, shaped exactly like a connection that was never established. Reporting
 * `0` there would claim the request never reached a server that demonstrably answered.
 */
class NoResponseStatusCodeTest {
    private lateinit var server: MockWebServer

    @JvmField
    @Rule
    var openTelemetryRumRule: OpenTelemetryRumRule = OpenTelemetryRumRule()

    @Before
    @Throws(IOException::class)
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        server.close()
    }

    @Test
    fun notFoundReadViaGetInputStreamIsNotReportedAsZero() {
        server.enqueue(MockResponse.Builder().code(404).body("missing").build())

        executeGetReadingInputStreamOnly(server.url("/missing").toString())

        assertThat(httpSpan().attributes.get(STATUS_CODE))
            .`as`("a 404 the server actually sent must be reported as 404, never as 0")
            .isEqualTo(404L)
    }

    @Test
    fun serverErrorReadViaGetInputStreamIsNotReportedAsZero() {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        executeGetReadingInputStreamOnly(server.url("/boom").toString())

        assertThat(httpSpan().attributes.get(STATUS_CODE)).isEqualTo(500L)
    }

    @Test
    fun successfulRequestReportsTheRealStatusCode() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        executeGetReadingInputStreamOnly(server.url("/ok").toString())

        assertThat(httpSpan().attributes.get(STATUS_CODE))
            .isEqualTo(200L)
    }

    @Test
    fun connectionRefusedReportsZeroStatusCode() {
        // The case the zero genuinely describes: nothing was listening, so the request never
        // reached a server. Shutting the mock server down frees the port.
        val url = server.url("/never").toString()
        server.close()

        executeGetReadingInputStreamOnly(url)

        assertThat(httpSpan().attributes.get(STATUS_CODE))
            .isEqualTo(0L)
    }

    private fun httpSpan(): SpanData =
        openTelemetryRumRule.inMemorySpanExporter.finishedSpanItems
            .first { it.attributes.get(REQUEST_METHOD) != null }

    private companion object {
        // semconv is not on this module's androidTest classpath; the sibling InstrumentationTest
        // spells the keys out for the same reason.
        val STATUS_CODE: AttributeKey<Long> = AttributeKey.longKey("http.response.status_code")
        val REQUEST_METHOD: AttributeKey<String> = AttributeKey.stringKey("http.request.method")
    }
}
