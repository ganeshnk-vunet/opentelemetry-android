/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.HttpAttributes
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
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
    fun genericIoFailureReportsZero() {
        assertThat(statusCodeFor(UNKNOWN_RESPONSE_CODE, IOException("stream closed"))).isEqualTo(0L)
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
    fun successfulResponseIsLeftToTheUpstreamExtractor() {
        assertThat(statusCodeFor(200, null)).isNull()
    }

    @Test
    fun errorResponseIsLeftToTheUpstreamExtractor() {
        // A 404 came from the server, so the real code is recorded upstream — not overwritten here.
        assertThat(statusCodeFor(404, null)).isNull()
    }

    @Test
    fun responseAccompaniedByAnErrorIsNotOverwritten() {
        assertThat(statusCodeFor(500, IOException("body read failed"))).isNull()
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
