/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp

import io.opentelemetry.android.test.common.OpenTelemetryRumRule
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end coverage for `http.response.status_code` on calls that never produced a response.
 *
 * The upstream HTTP attributes extractor omits the status code when it is not positive, so failed
 * calls would otherwise carry no status at all. Client-side failures that never reached the server
 * (DNS, connection refused, TLS) report `0`; timeouts keep the attribute absent because the request
 * may have been received and processed.
 *
 * Exercises the real instrumentation rather than the extractor in isolation, so extractor ordering
 * and the upstream filtering are covered too.
 */
class NoResponseStatusCodeTest {
    private lateinit var server: MockWebServer

    @get:Rule
    internal var openTelemetryRumRule: OpenTelemetryRumRule = OpenTelemetryRumRule()

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
    fun dnsFailureReportsZeroStatusCode() {
        val client = OkHttpClient.Builder().build()
        val request =
            Request
                .Builder()
                .url("http://host.invalid.example.does.not.resolve/test/")
                .build()

        runCatching { client.newCall(request).execute().close() }

        assertThat(statusCodeOfHttpSpan()).isEqualTo(0L)
    }

    @Test
    fun connectionRefusedReportsZeroStatusCode() {
        // Bind then immediately release a port so nothing is listening on it.
        val deadPort = server.port
        server.close()

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url("http://127.0.0.1:$deadPort/test/").build()

        runCatching { client.newCall(request).execute().close() }

        assertThat(statusCodeOfHttpSpan()).isEqualTo(0L)

        // Restore a server so tearDown has something to close.
        server = MockWebServer()
        server.start()
    }

    @Test
    fun timeoutLeavesStatusCodeAbsent() {
        // Headers are delayed past the read timeout, so the call fails without a response.
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .headersDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )

        val client =
            OkHttpClient
                .Builder()
                .readTimeout(Duration.ofMillis(300))
                .callTimeout(Duration.ofMillis(600))
                .build()
        val request = Request.Builder().url(server.url("/slow/")).build()

        val failure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()

        // Guard the premise: this must be a timeout, otherwise the assertion below proves nothing.
        assertThat(generateSequence(failure) { it.cause }.any { it is SocketTimeoutException })
            .isTrue()
        assertThat(statusCodeOfHttpSpan()).isNull()
    }

    @Test
    fun successfulResponseReportsRealStatusCode() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val client = OkHttpClient.Builder().build()
        client.newCall(Request.Builder().url(server.url("/ok/")).build()).execute().close()

        assertThat(statusCodeOfHttpSpan()).isEqualTo(200L)
    }

    @Test
    fun errorResponseReportsRealStatusCode() {
        server.enqueue(MockResponse.Builder().code(404).build())

        val client = OkHttpClient.Builder().build()
        client.newCall(Request.Builder().url(server.url("/missing/")).build()).execute().close()

        assertThat(statusCodeOfHttpSpan()).isEqualTo(404L)
    }

    @Test
    fun serverErrorReportsRealStatusCode() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val client = OkHttpClient.Builder().build()
        client.newCall(Request.Builder().url(server.url("/boom/")).build()).execute().close()

        assertThat(statusCodeOfHttpSpan()).isEqualTo(500L)
    }

    @Test
    fun midBodyFailureAfterA200ReportsTheRealStatusCode() {
        // The regression behind the coordinator fix. TimingTracingInterceptor records the response
        // as soon as chain.proceed() returns, but OkHttpCallCompletionCoordinator used to discard
        // it whenever an error was present — so a 200 that died mid-body reached the extractors as
        // (null, IOException), shaped exactly like a connection that was never established. It has
        // to keep the 200 it already received.
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("a".repeat(1024))
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url(server.url("/truncated/")).build()

        val failure =
            runCatching {
                client.newCall(request).execute().use { it.body.string() }
            }.exceptionOrNull()

        // Guard the premise: the body read must actually fail, or the assertion proves nothing.
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(statusCodeOfHttpSpan())
            .`as`("a 200 that failed mid-body must keep its status code, never report 0")
            .isEqualTo(200L)
    }

    /** Status code recorded on the emitted HTTP client span, or null when the attribute is absent. */
    private fun statusCodeOfHttpSpan(): Long? = httpSpan().attributes.get(STATUS_CODE_KEY)

    private fun httpSpan(): SpanData {
        val span =
            openTelemetryRumRule.inMemorySpanExporter.finishedSpanItems.firstOrNull { span ->
                span.attributes.get(REQUEST_METHOD_KEY) == "GET"
            }
        assertThat(span).describedAs("no HTTP client span was emitted").isNotNull()
        return span!!
    }

    private companion object {
        val STATUS_CODE_KEY: AttributeKey<Long> = AttributeKey.longKey("http.response.status_code")
        val REQUEST_METHOD_KEY: AttributeKey<String> = AttributeKey.stringKey("http.request.method")
    }
}
