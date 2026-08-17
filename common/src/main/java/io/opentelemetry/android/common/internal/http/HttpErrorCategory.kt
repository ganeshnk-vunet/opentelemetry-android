/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.http

import io.opentelemetry.api.common.AttributeKey
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

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
     * Reporting `0` asserts **"the request never reached the server"**, so this matches on the
     * specific exception types that prove it, not on the coarse [fromThrowable] categories. The
     * category buckets are far wider than the claim: [categoryForType] maps *any* unmatched
     * `IOException` to [IO], which includes failures proving the opposite — `StreamResetException`
     * (the server sent HTTP/2 RST_STREAM), `SocketException("Connection reset")` while reading a
     * response body, and `FileNotFoundException`, which is what `HttpURLConnection.getInputStream()`
     * raises for every response `>= 400`. Classifying those as "never got there" turned real 404s
     * and mid-body failures into `status_code = 0`.
     *
     * [SSL] has the same problem on a narrower scale: an `SSLException` raised during a mid-stream
     * read is not a handshake failure.
     *
     * Only pre-request failures qualify:
     * - `UnknownHostException` — DNS never resolved, so no connection was opened.
     * - `ConnectException` / `NoRouteToHostException` — the TCP connection never completed.
     * - `SSLHandshakeException` — TLS handshake failed, so the request was never sent.
     *
     * Everything else keeps the attribute absent, which is what the OpenTelemetry semantic
     * conventions prescribe when no response arrives. That deliberately includes timeouts (the
     * request may have been received and processed, the response merely arrived too late),
     * cancellation — which OkHttp surfaces as a plain `IOException("Canceled")` — and any failure
     * raised after bytes were already flowing.
     *
     * Only meaningful when no response was received; callers must check that first.
     */
    fun reportsZeroStatusCode(throwable: Throwable?): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (provesServerWasNeverReached(current)) return true
            current = current.cause
        }
        return false
    }

    private fun provesServerWasNeverReached(throwable: Throwable): Boolean =
        when (throwable) {
            // Ordered before the SocketException family below, which ConnectException extends.
            is UnknownHostException -> true
            is ConnectException -> true
            is NoRouteToHostException -> true
            // A handshake failure means no request bytes were ever sent. Deliberately not the
            // broader SSLException, which also covers mid-stream read failures.
            is SSLHandshakeException -> true
            else -> false
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
