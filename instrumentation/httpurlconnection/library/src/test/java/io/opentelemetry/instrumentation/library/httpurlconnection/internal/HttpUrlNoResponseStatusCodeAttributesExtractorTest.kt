/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.HttpAttributes
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLConnection
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HttpUrlNoResponseStatusCodeAttributesExtractorTest {
    private val connection = mockk<URLConnection>(relaxed = true)

    private fun statusCodeFor(
        response: Int?,
        error: Throwable?,
    ): Long? {
        val attributes = Attributes.builder()
        HttpUrlNoResponseStatusCodeAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            connection,
            response,
            error,
        )
        return attributes.build().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE)
    }

    @Test
    fun dnsFailureReportsZero() {
        // HttpUrlReplacements reports -1 as the sentinel when the request never produced a response.
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, UnknownHostException("no such host")))
            .isEqualTo(0L)
    }

    @Test
    fun connectionFailureReportsZero() {
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, ConnectException("connection refused")))
            .isEqualTo(0L)
    }

    @Test
    fun sslFailureReportsZero() {
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, SSLHandshakeException("handshake failed")))
            .isEqualTo(0L)
    }

    @Test
    fun genericIoFailureLeavesStatusCodeAbsent() {
        // A bare IOException does not prove the server was never reached.
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, IOException("stream closed"))).isNull()
    }

    @Test
    fun notFoundViaGetInputStreamLeavesStatusCodeAbsent() {
        // The regression this file exists to prevent. On Android
        // HttpURLConnectionImpl.getInputStream() throws FileNotFoundException for any response
        // >= 400, and reportWithThrowable passes -1 on every throwable path — so a plain 404
        // arrives here as (-1, FileNotFoundException). Reporting 0 would claim a request that got
        // a 404 never reached the server.
        assertThat(
            statusCodeFor(UNKNOWN_RESPONSE_CODE, FileNotFoundException("https://example/missing")),
        ).isNull()
    }

    @Test
    fun midBodyReadFailureLeavesStatusCodeAbsent() {
        // InstrumentedInputStream.read failing after a 200 also reports (-1, IOException).
        assertThat(
            statusCodeFor(UNKNOWN_RESPONSE_CODE, SocketException("Connection reset")),
        ).isNull()
    }

    @Test
    fun nullResponseReportsZero() {
        assertThat(statusCodeFor(null, UnknownHostException())).isEqualTo(0L)
    }

    @Test
    fun timeoutLeavesStatusCodeAbsent() {
        // The request may have reached the server, so semconv keeps the attribute unset.
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, SocketTimeoutException("timed out")))
            .isNull()
    }

    @Test
    fun abortedRequestLeavesStatusCodeAbsent() {
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, InterruptedIOException("aborted"))).isNull()
    }

    @Test
    fun wrappedTimeoutLeavesStatusCodeAbsent() {
        assertThat(
            statusCodeFor(UNKNOWN_RESPONSE_CODE, IOException("failed", SocketTimeoutException())),
        ).isNull()
    }

    @Test
    fun nonTransportFailureLeavesStatusCodeAbsent() {
        // Classifies as `unknown`: we cannot tell whether the server was reached, so claiming
        // "never got there" is not justified.
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, IllegalStateException("boom"))).isNull()
    }

    @Test
    fun successfulResponseIsLeftToTheUpstreamExtractor() {
        assertThat(statusCodeFor(200, null)).isNull()
    }

    @Test
    fun errorResponseIsLeftToTheUpstreamExtractor() {
        // A 404 came from the server, so the real code is recorded upstream — not overwritten here.
        assertThat(statusCodeFor(404, null)).isNull()
    }

    @Test
    fun bodyReadFailureAfterAnErrorResponseIsNotReportedAsZero() {
        // Deliberately -1 rather than 500: reportWithThrowable always passes -1, so (500,
        // IOException) never reaches onEnd and asserting on it would guard an impossible path.
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, IOException("body read failed"))).isNull()
    }

    @Test
    fun noErrorReportsNothing() {
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, null)).isNull()
    }

    private companion object {
        /** Mirrors `HttpUrlReplacements.UNKNOWN_RESPONSE_CODE`. */
        const val UNKNOWN_RESPONSE_CODE = -1
    }
}
