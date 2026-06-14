/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import java.net.URL
import java.net.URLConnection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HttpUrlConnectionTimingTest {
    @AfterEach
    fun tearDown() {
        HttpUrlConnectionTiming.clear()
    }

    @Test
    fun `start is idempotent and finalize returns elapsed milliseconds`() {
        val connection = URL("http://example.com").openConnection()

        HttpUrlConnectionTiming.start(connection)
        HttpUrlConnectionTiming.start(connection)

        val totalMs = HttpUrlConnectionTiming.removeAndFinalize(connection)

        assertThat(totalMs).isNotNull()
        assertThat(totalMs).isGreaterThanOrEqualTo(0L)
        assertThat(HttpUrlConnectionTiming.removeAndFinalize(connection)).isNull()
    }

    @Test
    fun `remove clears entry without finalizing`() {
        val connection = URL("http://example.com").openConnection()

        HttpUrlConnectionTiming.start(connection)
        HttpUrlConnectionTiming.remove(connection)

        assertThat(HttpUrlConnectionTiming.removeAndFinalize(connection)).isNull()
    }

    @Test
    fun `finalize computes duration from stored start time`() {
        val connection = object : URLConnection(URL("http://example.com")) {
            override fun connect() {
                // no-op
            }
        }

        HttpUrlConnectionTiming.start(connection)
        Thread.sleep(5)
        val totalMs = HttpUrlConnectionTiming.removeAndFinalize(connection)

        assertThat(totalMs).isNotNull()
        assertThat(totalMs).isGreaterThanOrEqualTo(1L)
    }
}
