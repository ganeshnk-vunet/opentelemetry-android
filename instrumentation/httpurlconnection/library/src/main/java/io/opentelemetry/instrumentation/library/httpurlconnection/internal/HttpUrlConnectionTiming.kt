/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

internal object HttpUrlConnectionTiming {
    private val startTimes = ConcurrentHashMap<URLConnection, Long>()

    fun start(connection: URLConnection) {
        startTimes.putIfAbsent(connection, System.nanoTime())
    }

    fun removeAndFinalize(connection: URLConnection): Long? {
        val startNanos = startTimes.remove(connection) ?: return null
        val elapsedNanos = System.nanoTime() - startNanos
        if (elapsedNanos < 0) {
            return null
        }
        return elapsedNanos / 1_000_000L
    }

    fun remove(connection: URLConnection) {
        startTimes.remove(connection)
    }

    fun clear() {
        startTimes.clear()
    }
}
