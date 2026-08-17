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
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class OkHttpNoResponseStatusCodeAttributesExtractorTest {
    private fun chain(canceled: Boolean = false): Interceptor.Chain {
        val call = mockk<Call>(relaxed = true).also { every { it.isCanceled() } returns canceled }
        return mockk<Interceptor.Chain>(relaxed = true).also { every { it.call() } returns call }
    }

    private fun statusCodeFor(
        response: Response?,
        error: Throwable?,
        chain: Interceptor.Chain = chain(),
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
    fun genericIoFailureLeavesStatusCodeAbsent() {
        // A bare IOException does not prove the server was never reached — it covers connection
        // resets and body-stream failures, which prove the opposite.
        assertThat(statusCodeFor(null, IOException("socket closed"))).isNull()
    }

    @Test
    fun transportFailureWithNoRecordedResponseLeavesStatusCodeAbsent() {
        // Defence in depth, not the production shape of a mid-body failure. On the default
        // captureNetworkTimingPhases path OkHttpCallCompletionCoordinator now reports the response
        // it already recorded alongside the error, so "200 then socket reset while streaming"
        // reaches onEnd as (Response(200), IOException) and exits on the `response != null` branch
        // — see midBodyFailureAfterA200ReportsTheRealStatusCode in the testing module.
        //
        // This covers the case where no response was ever recorded, e.g. the failure landed before
        // chain.proceed() returned. A connection reset still is not evidence the server was never
        // reached, so the attribute stays absent rather than claiming 0.
        assertThat(statusCodeFor(null, SocketException("Connection reset"))).isNull()
    }

    @Test
    fun http2StreamResetLeavesStatusCodeAbsent() {
        // An RST_STREAM is proof the server was reached: it sent the reset.
        assertThat(statusCodeFor(null, StreamResetException(ErrorCode.CANCEL))).isNull()
    }

    @Test
    fun midStreamSslFailureLeavesStatusCodeAbsent() {
        // SSLException raised during a read is not a handshake failure, so it says nothing about
        // whether the request was delivered.
        assertThat(statusCodeFor(null, SSLException("read error"))).isNull()
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
    fun canceledCallLeavesStatusCodeAbsent() {
        // OkHttp raises cancellation as a plain IOException("Canceled") — not an
        // InterruptedIOException — so it classifies as `io` and the exception type alone cannot
        // distinguish it from a genuine transport failure. A cancelled call may have reached the
        // server, so it must not claim status 0.
        assertThat(
            statusCodeFor(null, IOException("Canceled"), chain(canceled = true)),
        ).isNull()
    }

    @Test
    fun aGenuinePreRequestFailureStillReportsZeroWhenTheCallWasNotCanceled() {
        // Guards the cancel carve-out against over-reaching: only an actually-cancelled call is
        // exempt, not every failure on a call whose message happens to look like one.
        assertThat(
            statusCodeFor(null, ConnectException("Canceled"), chain(canceled = false)),
        ).isEqualTo(0L)
    }

    @Test
    fun nonTransportFailureLeavesStatusCodeAbsent() {
        // Classifies as `unknown`: we cannot tell whether the server was reached, so claiming
        // "never got there" is not justified. An interceptor can throw well after a response.
        assertThat(statusCodeFor(null, IllegalStateException("interceptor blew up"))).isNull()
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
    fun bodyReadFailureAfterAnErrorResponseIsNotReportedAsZero() {
        // (null, IOException) rather than (Response(500), IOException) because this asserts the
        // no-recorded-response path specifically: when the coordinator has a response it now
        // passes it through, and the `response != null` branch handles it (covered by
        // errorResponseIsLeftToTheUpstreamExtractor).
        assertThat(statusCodeFor(null, IOException("body read failed"))).isNull()
    }

    @Test
    fun noResponseAndNoErrorReportsNothing() {
        assertThat(statusCodeFor(null, null)).isNull()
    }
}
