/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.HttpAttributes
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Interceptor
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OkHttpNoResponseStatusCodeAttributesExtractorTest {
    private val chain = mockk<Interceptor.Chain>(relaxed = true)

    private fun statusCodeFor(
        response: Response?,
        error: Throwable?,
    ): Long? {
        val attributes = Attributes.builder()
        OkHttpNoResponseStatusCodeAttributesExtractor.onEnd(
            attributes,
            Context.root(),
            chain,
            response,
            error,
        )
        return attributes.build().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE)
    }

    private fun responseWithCode(code: Int): Response =
        mockk<Response>(relaxed = true).also { every { it.code } returns code }

    @Test
    fun dnsFailureReportsZero() {
        assertThat(statusCodeFor(null, UnknownHostException("no such host"))).isEqualTo(0L)
    }

    @Test
    fun connectionFailureReportsZero() {
        assertThat(statusCodeFor(null, ConnectException("connection refused"))).isEqualTo(0L)
    }

    @Test
    fun sslFailureReportsZero() {
        assertThat(statusCodeFor(null, SSLHandshakeException("handshake failed"))).isEqualTo(0L)
    }

    @Test
    fun genericIoFailureReportsZero() {
        assertThat(statusCodeFor(null, IOException("socket closed"))).isEqualTo(0L)
    }

    @Test
    fun timeoutLeavesStatusCodeAbsent() {
        // The request may have reached the server, so semconv keeps the attribute unset.
        assertThat(statusCodeFor(null, SocketTimeoutException("read timed out"))).isNull()
    }

    @Test
    fun abortedRequestLeavesStatusCodeAbsent() {
        assertThat(statusCodeFor(null, InterruptedIOException("canceled"))).isNull()
    }

    @Test
    fun wrappedTimeoutLeavesStatusCodeAbsent() {
        // OkHttp commonly wraps the underlying cause.
        assertThat(
            statusCodeFor(null, IOException("call failed", SocketTimeoutException())),
        ).isNull()
    }

    @Test
    fun successfulResponseIsLeftToTheUpstreamExtractor() {
        assertThat(statusCodeFor(responseWithCode(200), null)).isNull()
    }

    @Test
    fun errorResponseIsLeftToTheUpstreamExtractor() {
        // A 404 came from the server, so the real code is recorded upstream — not overwritten here.
        assertThat(statusCodeFor(responseWithCode(404), null)).isNull()
    }

    @Test
    fun responseAccompaniedByAnErrorIsNotOverwritten() {
        assertThat(statusCodeFor(responseWithCode(500), IOException("body read failed"))).isNull()
    }

    @Test
    fun noResponseAndNoErrorReportsNothing() {
        assertThat(statusCodeFor(null, null)).isNull()
    }
}
