/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

internal class CallTimingState {
    var callStartNanos: Long? = null
    var callEndNanos: Long? = null
    var dnsStartNanos: Long? = null
    var dnsEndNanos: Long? = null
    var connectStartNanos: Long? = null
    var connectEndNanos: Long? = null
    var secureConnectStartNanos: Long? = null
    var secureConnectEndNanos: Long? = null
    var connectionAcquiredNanos: Long? = null
    var proxySelectStartNanos: Long? = null
    var proxySelectEndNanos: Long? = null
    var requestHeadersStartNanos: Long? = null
    var requestHeadersEndNanos: Long? = null
    var requestBodyStartNanos: Long? = null
    var requestBodyEndNanos: Long? = null
    var responseHeadersStartNanos: Long? = null
    var responseHeadersEndNanos: Long? = null
    var responseBodyStartNanos: Long? = null
    var responseBodyEndNanos: Long? = null
    var failed: Boolean = false
    var phasesComplete: Boolean = true

    fun finalizeTiming(nowNanos: Long = System.nanoTime()): OkHttpTimingResult {
        val callEnd = callEndNanos ?: nowNanos
        return OkHttpTimingResult(
            dnsMs = durationMs(dnsStartNanos, dnsEndNanos),
            connectMs = durationMs(connectStartNanos, connectEndNanos),
            tlsMs = durationMs(secureConnectStartNanos, secureConnectEndNanos),
            ttfbMs = durationMs(requestHeadersStartNanos, responseHeadersStartNanos),
            downloadMs = durationMs(responseHeadersStartNanos, responseBodyEndNanos),
            totalMs = durationMs(callStartNanos, callEnd),
            poolWaitMs = durationMs(callStartNanos, connectionAcquiredNanos),
            requestHeadersMs = durationMs(requestHeadersStartNanos, requestHeadersEndNanos),
            requestBodyMs = durationMs(requestBodyStartNanos, requestBodyEndNanos),
            responseHeadersMs = durationMs(responseHeadersStartNanos, responseHeadersEndNanos),
            proxySelectMs = durationMs(proxySelectStartNanos, proxySelectEndNanos),
            phasesComplete = phasesComplete && !failed,
        )
    }

    private fun durationMs(
        startNanos: Long?,
        endNanos: Long?,
    ): Long? {
        if (startNanos == null || endNanos == null) {
            return null
        }
        if (endNanos < startNanos) {
            return null
        }
        return (endNanos - startNanos) / 1_000_000L
    }
}
