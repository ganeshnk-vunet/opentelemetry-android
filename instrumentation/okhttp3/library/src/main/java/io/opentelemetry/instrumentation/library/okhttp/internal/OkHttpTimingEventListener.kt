/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal class OkHttpTimingEventListener : EventListener() {
    override fun callStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = System.nanoTime()
    }

    override fun proxySelectStart(
        call: Call,
        url: HttpUrl,
    ) {
        OkHttpCallTimingStore.stateFor(call).proxySelectStartNanos = System.nanoTime()
    }

    override fun proxySelectEnd(
        call: Call,
        url: HttpUrl,
        proxies: List<Proxy>,
    ) {
        OkHttpCallTimingStore.stateFor(call).proxySelectEndNanos = System.nanoTime()
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        OkHttpCallTimingStore.stateFor(call).dnsStartNanos = System.nanoTime()
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        OkHttpCallTimingStore.stateFor(call).dnsEndNanos = System.nanoTime()
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        OkHttpCallTimingStore.stateFor(call).connectStartNanos = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).secureConnectStartNanos = System.nanoTime()
    }

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        OkHttpCallTimingStore.stateFor(call).secureConnectEndNanos = System.nanoTime()
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        OkHttpCallTimingStore.stateFor(call).connectEndNanos = System.nanoTime()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.failed = true
        state.phasesComplete = false
    }

    override fun connectionAcquired(
        call: Call,
        connection: Connection,
    ) {
        val state = OkHttpCallTimingStore.stateFor(call)
        if (state.connectionAcquiredNanos == null) {
            state.connectionAcquiredNanos = System.nanoTime()
        }
    }

    override fun requestHeadersStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).requestHeadersStartNanos = System.nanoTime()
    }

    override fun requestHeadersEnd(
        call: Call,
        request: Request,
    ) {
        OkHttpCallTimingStore.stateFor(call).requestHeadersEndNanos = System.nanoTime()
    }

    override fun requestBodyStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).requestBodyStartNanos = System.nanoTime()
    }

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        OkHttpCallTimingStore.stateFor(call).requestBodyEndNanos = System.nanoTime()
    }

    override fun requestFailed(
        call: Call,
        ioe: IOException,
    ) {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.failed = true
        state.phasesComplete = false
    }

    override fun responseHeadersStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).responseHeadersStartNanos = System.nanoTime()
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        OkHttpCallTimingStore.stateFor(call).responseHeadersEndNanos = System.nanoTime()
    }

    override fun responseBodyStart(call: Call) {
        OkHttpCallTimingStore.stateFor(call).responseBodyStartNanos = System.nanoTime()
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        OkHttpCallTimingStore.stateFor(call).responseBodyEndNanos = System.nanoTime()
    }

    override fun responseFailed(
        call: Call,
        ioe: IOException,
    ) {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.failed = true
        state.phasesComplete = false
    }

    override fun callEnd(call: Call) {
        OkHttpCallTimingStore.stateFor(call).callEndNanos = System.nanoTime()
    }

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.failed = true
        state.phasesComplete = false
        state.callEndNanos = System.nanoTime()
    }

    override fun canceled(call: Call) {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.failed = true
        state.phasesComplete = false
    }
}
