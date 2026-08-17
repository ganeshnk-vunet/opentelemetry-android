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
     * Reporting `0` asserts "the request never reached the server", so this is an allowlist of the
     * categories that actually justify that claim — [DNS], [SSL] and [IO]. Anything else keeps the
     * attribute absent, which is what the OpenTelemetry semantic conventions prescribe when no
     * response arrives.
     *
     * Excluded deliberately:
     * - [TIMEOUT] — the request may well have reached the server and been processed; the response
     *   simply did not arrive in time.
     * - [UNKNOWN] — by definition we cannot tell whether the server was reached, and that is
     *   exactly where asserting "never got there" is least defensible. A non-transport failure
     *   (an interceptor throwing, say) can be raised long after the server responded.
     *
     * [IO] covers transport failures, but note that some clients surface *cancellation* as a plain
     * `IOException`: OkHttp throws `IOException("Canceled")`. A cancelled call may have reached the
     * server, so callers that can detect cancellation must check for it before consulting this —
     * see `OkHttpNoResponseStatusCodeAttributesExtractor`.
     *
     * Only meaningful when no response was received — callers must check that first.
     */
    fun reportsZeroStatusCode(throwable: Throwable?): Boolean =
        when (fromThrowable(throwable)) {
            DNS, SSL, IO -> true
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
