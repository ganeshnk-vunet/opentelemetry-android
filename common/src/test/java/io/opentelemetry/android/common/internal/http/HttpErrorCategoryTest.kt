/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HttpErrorCategoryTest {
    @Test
    fun fromThrowable_nullReturnsNull() {
        assertThat(HttpErrorCategory.fromThrowable(null)).isNull()
    }

    @Test
    fun fromThrowable_socketTimeout() {
        assertThat(HttpErrorCategory.fromThrowable(SocketTimeoutException())).isEqualTo(HttpErrorCategory.TIMEOUT)
    }

    @Test
    fun fromThrowable_interruptedIo() {
        assertThat(HttpErrorCategory.fromThrowable(InterruptedIOException())).isEqualTo(HttpErrorCategory.TIMEOUT)
    }

    @Test
    fun fromThrowable_unknownHost() {
        assertThat(HttpErrorCategory.fromThrowable(UnknownHostException())).isEqualTo(HttpErrorCategory.DNS)
    }

    @Test
    fun fromThrowable_unknownHostInCauseChain() {
        val error = IOException("connection failed", UnknownHostException("example.invalid"))
        assertThat(HttpErrorCategory.fromThrowable(error)).isEqualTo(HttpErrorCategory.DNS)
    }

    @Test
    fun fromThrowable_sslHandshake() {
        assertThat(HttpErrorCategory.fromThrowable(SSLHandshakeException("handshake failed")))
            .isEqualTo(HttpErrorCategory.SSL)
    }

    @Test
    fun fromThrowable_certificate() {
        assertThat(HttpErrorCategory.fromThrowable(CertificateException("bad cert")))
            .isEqualTo(HttpErrorCategory.SSL)
    }

    @Test
    fun fromThrowable_genericIo() {
        assertThat(HttpErrorCategory.fromThrowable(ConnectException())).isEqualTo(HttpErrorCategory.IO)
    }

    @Test
    fun fromThrowable_unknownWhenNotIoRelated() {
        assertThat(HttpErrorCategory.fromThrowable(IllegalStateException("boom")))
            .isEqualTo(HttpErrorCategory.UNKNOWN)
    }

    @Test
    fun fromStatusCode_clientErrors() {
        assertThat(HttpErrorCategory.fromStatusCode(404)).isEqualTo(HttpErrorCategory.HTTP_CLIENT)
        assertThat(HttpErrorCategory.fromStatusCode(500)).isEqualTo(HttpErrorCategory.HTTP_CLIENT)
    }

    @Test
    fun fromStatusCode_successReturnsNull() {
        assertThat(HttpErrorCategory.fromStatusCode(200)).isNull()
        assertThat(HttpErrorCategory.fromStatusCode(399)).isNull()
    }

    @Test
    fun reportsZeroStatusCode_trueForFailuresThatNeverReachedTheServer() {
        assertThat(HttpErrorCategory.reportsZeroStatusCode(UnknownHostException())).isTrue()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(ConnectException("refused"))).isTrue()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(SSLHandshakeException("bad cert"))).isTrue()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(CertificateException("untrusted"))).isTrue()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(IOException("read failed"))).isTrue()
    }

    @Test
    fun reportsZeroStatusCode_falseForUnknownFailures() {
        // `unknown` is precisely where we cannot tell whether the server was reached, so asserting
        // "never got there" is least defensible. A non-transport failure can be raised long after
        // the response arrived (an interceptor throwing, for instance).
        assertThat(HttpErrorCategory.reportsZeroStatusCode(IllegalStateException("boom"))).isFalse()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(RuntimeException("boom"))).isFalse()
        assertThat(HttpErrorCategory.fromThrowable(IllegalStateException("boom")))
            .isEqualTo(HttpErrorCategory.UNKNOWN)
    }

    @Test
    fun reportsZeroStatusCode_falseForTimeouts() {
        // A timeout may still have reached and been processed by the server, so claiming a
        // status would be misleading; semconv leaves the attribute absent.
        assertThat(HttpErrorCategory.reportsZeroStatusCode(SocketTimeoutException())).isFalse()
        assertThat(HttpErrorCategory.reportsZeroStatusCode(InterruptedIOException())).isFalse()
    }

    @Test
    fun reportsZeroStatusCode_falseWithoutAnError() {
        assertThat(HttpErrorCategory.reportsZeroStatusCode(null)).isFalse()
    }

    @Test
    fun reportsZeroStatusCode_followsCauseChain() {
        // Clients commonly wrap the real cause; a wrapped timeout must still be treated as one.
        assertThat(
            HttpErrorCategory.reportsZeroStatusCode(IOException("wrapped", SocketTimeoutException())),
        ).isFalse()
        assertThat(
            HttpErrorCategory.reportsZeroStatusCode(IOException("wrapped", UnknownHostException())),
        ).isTrue()
    }
}
