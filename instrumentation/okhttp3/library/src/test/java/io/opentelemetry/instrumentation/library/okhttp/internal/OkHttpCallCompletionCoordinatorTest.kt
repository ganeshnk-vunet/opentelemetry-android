/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import java.io.IOException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpCallCompletionCoordinatorTest {
    private lateinit var call: Call
    private lateinit var span: Span
    private lateinit var instrumenter: Instrumenter<Interceptor.Chain, Response>
    private lateinit var chain: Interceptor.Chain

    @BeforeEach
    fun setUp() {
        call = mockk(relaxed = true)
        span = mockk(relaxed = true)
        chain = mockk(relaxed = true)
        instrumenter = mockk(relaxed = true)
        every { span.setAttribute(any<String>(), any<Long>()) } returns span
        every { span.setAttribute(any<String>(), any<Boolean>()) } returns span
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
        OkHttpCallCompletionCoordinator.configure(instrumenter, OkHttpTimingSpanEnricher())
    }

    @AfterEach
    fun tearDown() {
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
    }

    @Test
    fun `onCallEnd finalizes timing after response body and ends traced span`() {
        val state = OkHttpCallTimingStore.stateFor(call)
        state.callStartNanos = 0L
        state.responseHeadersStartNanos = 10_000_000L
        state.responseBodyEndNanos = 30_000_000L
        val response = mockk<Response>(relaxed = true)
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)
        OkHttpCallCompletionCoordinator.setResponse(call, response)

        OkHttpCallCompletionCoordinator.onCallEnd(call)

        verify { span.setAttribute(OkHttpTimingAttributes.DOWNLOAD_MS, 20L) }
        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, true) }
        verify { instrumenter.end(Context.root(), chain, response, null) }
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }

    @Test
    fun `onCallEnd discards timing for untraced calls`() {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L

        OkHttpCallCompletionCoordinator.onCallEnd(call)

        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
        verify(exactly = 0) { instrumenter.end(any(), any(), any(), any()) }
    }

    @Test
    fun `onCallFailed ends traced span with error`() {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L
        val error = IOException("boom")
        OkHttpCallCompletionCoordinator.registerTraced(call, Context.root(), chain, span)

        OkHttpCallCompletionCoordinator.onCallFailed(call, error)

        verify { instrumenter.end(Context.root(), chain, null, error) }
        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, false) }
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }
}
