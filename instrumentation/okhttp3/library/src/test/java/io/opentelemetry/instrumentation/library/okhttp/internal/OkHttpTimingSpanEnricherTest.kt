/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import okhttp3.Call
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpTimingSpanEnricherTest {
    private lateinit var enricher: OkHttpTimingSpanEnricher
    private lateinit var call: Call
    private lateinit var span: Span

    @BeforeEach
    fun setUp() {
        enricher = OkHttpTimingSpanEnricher()
        call = mockk(relaxed = true)
        span = mockk(relaxed = true)
        OkHttpCallTimingStore.clear()
        every { span.setAttribute(any<String>(), any<Long>()) } returns span
        every { span.setAttribute(any<String>(), any<Boolean>()) } returns span
    }

    @Test
    fun `adds attributes and events for recorded phases`() {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.callStartNanos = 0L
        state.callEndNanos = 50_000_000L
        state.dnsStartNanos = 0L
        state.dnsEndNanos = 5_000_000L
        state.requestHeadersStartNanos = 10_000_000L
        state.responseHeadersStartNanos = 20_000_000L

        enricher.enrich(span, call)

        verify { span.setAttribute(OkHttpTimingAttributes.DNS_MS, 5L) }
        verify { span.setAttribute(OkHttpTimingAttributes.TTFB_MS, 10L) }
        verify { span.setAttribute(OkHttpTimingAttributes.TOTAL_MS, 50L) }
        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, true) }
        verify { span.addEvent(OkHttpTimingAttributes.EVENT_DNS, any<Attributes>()) }
    }
}
