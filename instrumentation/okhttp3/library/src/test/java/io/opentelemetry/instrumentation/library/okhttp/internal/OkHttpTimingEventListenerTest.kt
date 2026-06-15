/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

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

class OkHttpTimingEventListenerTest {
    private lateinit var listener: OkHttpTimingEventListener
    private lateinit var call: Call
    private lateinit var span: Span
    private lateinit var instrumenter: Instrumenter<Interceptor.Chain, Response>

    @BeforeEach
    fun setUp() {
        listener = OkHttpTimingEventListener()
        call = mockk(relaxed = true)
        span = mockk(relaxed = true)
        instrumenter = mockk(relaxed = true)
        io.mockk.every { span.setAttribute(any<String>(), any<Long>()) } returns span
        io.mockk.every { span.setAttribute(any<String>(), any<Boolean>()) } returns span
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
    fun `records call failure as incomplete`() {
        OkHttpCallCompletionCoordinator.registerTraced(
            call,
            Context.root(),
            mockk(relaxed = true),
            span,
        )
        listener.callStart(call)
        listener.callFailed(call, IOException("boom"))

        verify { span.setAttribute(OkHttpTimingAttributes.PHASES_COMPLETE, false) }
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }

    @Test
    fun `callEnd does not recreate timing state after completion`() {
        listener.callStart(call)
        listener.callEnd(call)
        listener.callEnd(call)

        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }

    @Test
    fun `responseBodyEnd updates existing timing state only`() {
        listener.callStart(call)
        OkHttpCallTimingStore.discard(call)

        listener.responseBodyEnd(call, 10L)

        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }
}
