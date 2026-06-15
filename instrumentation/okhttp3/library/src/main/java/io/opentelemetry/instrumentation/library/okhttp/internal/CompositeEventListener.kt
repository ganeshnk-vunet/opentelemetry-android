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

internal class CompositeEventListener(
    private val first: EventListener,
    private val second: EventListener,
) : EventListener() {
    override fun callStart(call: Call) {
        first.callStart(call)
        second.callStart(call)
    }

    override fun proxySelectStart(
        call: Call,
        url: HttpUrl,
    ) {
        first.proxySelectStart(call, url)
        second.proxySelectStart(call, url)
    }

    override fun proxySelectEnd(
        call: Call,
        url: HttpUrl,
        proxies: List<Proxy>,
    ) {
        first.proxySelectEnd(call, url, proxies)
        second.proxySelectEnd(call, url, proxies)
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        first.dnsStart(call, domainName)
        second.dnsStart(call, domainName)
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        first.dnsEnd(call, domainName, inetAddressList)
        second.dnsEnd(call, domainName, inetAddressList)
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        first.connectStart(call, inetSocketAddress, proxy)
        second.connectStart(call, inetSocketAddress, proxy)
    }

    override fun secureConnectStart(call: Call) {
        first.secureConnectStart(call)
        second.secureConnectStart(call)
    }

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        first.secureConnectEnd(call, handshake)
        second.secureConnectEnd(call, handshake)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        first.connectEnd(call, inetSocketAddress, proxy, protocol)
        second.connectEnd(call, inetSocketAddress, proxy, protocol)
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        first.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        second.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }

    override fun connectionAcquired(
        call: Call,
        connection: Connection,
    ) {
        first.connectionAcquired(call, connection)
        second.connectionAcquired(call, connection)
    }

    override fun connectionReleased(
        call: Call,
        connection: Connection,
    ) {
        first.connectionReleased(call, connection)
        second.connectionReleased(call, connection)
    }

    override fun requestHeadersStart(call: Call) {
        first.requestHeadersStart(call)
        second.requestHeadersStart(call)
    }

    override fun requestHeadersEnd(
        call: Call,
        request: Request,
    ) {
        first.requestHeadersEnd(call, request)
        second.requestHeadersEnd(call, request)
    }

    override fun requestBodyStart(call: Call) {
        first.requestBodyStart(call)
        second.requestBodyStart(call)
    }

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        first.requestBodyEnd(call, byteCount)
        second.requestBodyEnd(call, byteCount)
    }

    override fun requestFailed(
        call: Call,
        ioe: IOException,
    ) {
        first.requestFailed(call, ioe)
        second.requestFailed(call, ioe)
    }

    override fun responseHeadersStart(call: Call) {
        first.responseHeadersStart(call)
        second.responseHeadersStart(call)
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        first.responseHeadersEnd(call, response)
        second.responseHeadersEnd(call, response)
    }

    override fun responseBodyStart(call: Call) {
        first.responseBodyStart(call)
        second.responseBodyStart(call)
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        first.responseBodyEnd(call, byteCount)
        second.responseBodyEnd(call, byteCount)
    }

    override fun responseFailed(
        call: Call,
        ioe: IOException,
    ) {
        first.responseFailed(call, ioe)
        second.responseFailed(call, ioe)
    }

    override fun callEnd(call: Call) {
        first.callEnd(call)
        second.callEnd(call)
    }

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) {
        first.callFailed(call, ioe)
        second.callFailed(call, ioe)
    }

    override fun canceled(call: Call) {
        first.canceled(call)
        second.canceled(call)
    }

    override fun satisfactionFailure(
        call: Call,
        response: Response,
    ) {
        first.satisfactionFailure(call, response)
        second.satisfactionFailure(call, response)
    }
}
