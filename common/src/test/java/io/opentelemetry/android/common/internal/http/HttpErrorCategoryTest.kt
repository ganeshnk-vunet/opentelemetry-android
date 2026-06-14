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
}
