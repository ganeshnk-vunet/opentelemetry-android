/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import io.opentelemetry.api.common.AttributeKey
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Shared HTTP error category constants and classification helpers for HTTP client instrumentations.
 *
 * This type is in an `internal`-named package and is **not** part of the stable public API.
 */
object HttpErrorCategory {
    @JvmField
    val ATTRIBUTE_KEY: AttributeKey<String> = AttributeKey.stringKey("http.error.category")

    const val TIMEOUT: String = "timeout"
    const val DNS: String = "dns"
    const val SSL: String = "ssl"
    const val IO: String = "io"
    const val HTTP_CLIENT: String = "http_client"
    const val UNKNOWN: String = "unknown"

    fun fromThrowable(throwable: Throwable?): String? {
        if (throwable == null) return null
        return classifyThrowable(throwable)
    }

    fun fromStatusCode(code: Int): String? {
        if (code >= 400) return HTTP_CLIENT
        return null
    }

    /**
     * Whether a client-side failure should report `http.response.status_code = 0`.
     *
     * A request that fails before any response is received (DNS resolution failure, connection
     * refused, TLS handshake failure, other I/O errors) reports `0` so the backend can distinguish
     * "never reached the server" from "no telemetry at all".
     *
     * Timeouts and aborts are deliberately excluded: the request may well have reached the server
     * and been processed, so claiming a status is misleading. Those keep the attribute absent,
     * which is what the OpenTelemetry semantic conventions prescribe when no response arrives.
     *
     * Only meaningful when no response was received — callers must check that first.
     */
    fun reportsZeroStatusCode(throwable: Throwable?): Boolean {
        val category = fromThrowable(throwable) ?: return false
        return category != TIMEOUT
    }

    private fun classifyThrowable(throwable: Throwable): String {
        var ioCategory: String? = null
        var current: Throwable? = throwable
        while (current != null) {
            when (val category = categoryForType(current)) {
                TIMEOUT, DNS, SSL -> return category
                IO -> ioCategory = IO
                null -> Unit
            }
            current = current.cause
        }
        return ioCategory ?: UNKNOWN
    }

    private fun categoryForType(throwable: Throwable): String? =
        when (throwable) {
            is SocketTimeoutException -> TIMEOUT
            is InterruptedIOException -> TIMEOUT
            is UnknownHostException -> DNS
            is SSLException -> SSL
            is CertificateException -> SSL
            is IOException -> IO
            else -> null
        }
}
