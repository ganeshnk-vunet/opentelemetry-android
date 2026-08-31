/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.anr

import android.os.Handler
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AnrWatcherTest {
    private lateinit var handler: Handler
    private lateinit var mainThread: Thread
    private lateinit var tracer: Tracer
    private lateinit var spanBuilder: SpanBuilder
    private lateinit var span: Span

    @Before
    fun setup() {
        handler = mockk()
        mainThread = Thread.currentThread()
        tracer = mockk()
        spanBuilder = mockk(relaxed = true)
        span = mockk(relaxed = true)

        every { tracer.spanBuilder(any()) } returns spanBuilder
        every { spanBuilder.setAllAttributes(any<Attributes>()) } returns spanBuilder
        every { spanBuilder.startSpan() } returns span
        every { span.end() } returns Unit
    }

    /**
     * The other tests here only verify that `setAllAttributes` was called, so they would not
     * notice an attribute going missing. This one inspects what was actually passed.
     */
    @Test
    fun anr_span_carries_error_runtime() {
        val captured = slot<Attributes>()
        every { spanBuilder.setAllAttributes(capture(captured)) } returns spanBuilder
        val anrWatcher = AnrWatcher(handler, mainThread, tracer, emptyList(), 1)
        every { handler.post(any()) } returns true

        for (i in 0..4) {
            anrWatcher.run()
        }

        assertEquals("jvm", captured.captured.get(AttributeKey.stringKey("error.runtime")))
    }

    @Test
    fun mainThreadDisappearing() {
        val anrWatcher = AnrWatcher(handler, mainThread, tracer)
        for (i in 0..4) {
            every { handler.post(any()) } returns false
            anrWatcher.run()
        }
        verify { tracer wasNot Called }
    }

    @Test
    fun noAnr() {
        val anrWatcher = AnrWatcher(handler, mainThread, tracer)
        for (i in 0..4) {
            every { handler.post(any()) } answers {
                val callback = it.invocation.args[0] as Runnable
                callback.run()
                true
            }

            anrWatcher.run()
        }
        verify { tracer wasNot Called }
    }

    @Test
    fun noAnr_temporaryPause() {
        val anrWatcher = AnrWatcher(handler, mainThread, tracer)
        for (i in 0..4) {
            val index = i
            every { handler.post(any()) } answers {
                val callback = it.invocation.args[0] as Runnable
                // have it fail once
                if (index != 3) {
                    callback.run()
                }
                true
            }
            anrWatcher.run()
        }
        verify { tracer wasNot Called }
    }

    @Test
    fun anr_detected() {
        val anrWatcher = AnrWatcher(handler, mainThread, tracer, emptyList(), 1)
        every { handler.post(any()) } returns true

        for (i in 0..4) {
            anrWatcher.run()
        }
        verify(exactly = 1) { tracer.spanBuilder("device.anr") }
        verify(exactly = 1) { spanBuilder.setAllAttributes(any<Attributes>()) }
        verify(exactly = 1) { spanBuilder.startSpan() }
        verify(exactly = 1) { span.end() }

        for (i in 0..3) {
            anrWatcher.run()
        }
        // Still just the 1 time
        verify(exactly = 1) { tracer.spanBuilder("device.anr") }
        verify(exactly = 1) { span.end() }

        anrWatcher.run()

        verify(exactly = 2) { tracer.spanBuilder("device.anr") }
        verify(exactly = 2) { spanBuilder.setAllAttributes(any<Attributes>()) }
        verify(exactly = 2) { spanBuilder.startSpan() }
        verify(exactly = 2) { span.end() }
    }
}
