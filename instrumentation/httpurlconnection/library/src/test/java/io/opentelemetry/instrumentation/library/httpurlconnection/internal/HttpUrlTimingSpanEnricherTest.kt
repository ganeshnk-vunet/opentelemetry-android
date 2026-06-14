/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.httpurlconnection.internal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import java.net.URL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpUrlTimingSpanEnricherTest {
    private lateinit var connection: java.net.URLConnection
    private lateinit var span: Span

    @BeforeEach
    fun setUp() {
        connection = URL("http://example.com").openConnection()
        span = mockk(relaxed = true)
        every { span.setAttribute(any<String>(), any<Long>()) } returns span
        every { span.setAttribute(any<String>(), any<Boolean>()) } returns span
        HttpUrlConnectionTiming.clear()
    }

    @AfterEach
    fun tearDown() {
        HttpUrlConnectionTiming.clear()
    }

    @Test
    fun `adds total timing attributes and http call event`() {
        HttpUrlConnectionTiming.start(connection)

        HttpUrlTimingSpanEnricher.enrich(span, connection)

        verify { span.setAttribute(HttpUrlConnectionTimingAttributes.TOTAL_MS, any<Long>()) }
        verify { span.setAttribute(HttpUrlConnectionTimingAttributes.PHASES_SUPPORTED, false) }
        verify { span.addEvent(HttpUrlConnectionTimingAttributes.EVENT_CALL, any<Attributes>()) }
    }
}
