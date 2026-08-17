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
    fun fileNotFoundWithNoRetrievableCodeLeavesStatusCodeAbsent() {
        // Defence in depth, not the production shape of a 404. `getInputStream()` raises
        // FileNotFoundException for any response >= 400, but `responseCodeOrUnknown` then supplies
        // the real code, so a 404 reaches onEnd as (404, FileNotFoundException) and exits on the
        // `response > 0` branch — see NoResponseStatusCodeTest in the testing module, which
        // exercises that against a real server.
        //
        // This covers the remaining fallback: `getResponseCode()` itself throwing, leaving only
        // the -1 sentinel. Even then FileNotFoundException means a response existed, so claiming
        // the request never reached the server would be wrong.
        assertThat(
            statusCodeFor(UNKNOWN_RESPONSE_CODE, FileNotFoundException("https://example/missing")),
        ).isNull()
    }

    @Test
    fun transportFailureWithNoRetrievableCodeLeavesStatusCodeAbsent() {
        // Same fallback as above for a mid-body failure: normally the 200 is recovered and this
        // exits on `response > 0`, so -1 only survives when no code can be retrieved at all. A
        // connection reset is not evidence the server was never reached either way.
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
        // -1 rather than 500 because this asserts the sentinel path specifically: when a code is
        // available `reportWithThrowable` now passes it and the `response > 0` branch handles it
        // (covered by errorResponseIsLeftToTheUpstreamExtractor).
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
