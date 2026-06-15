/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.mockk
import io.mockk.verify
import okhttp3.Call
import okhttp3.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpTimingEventListenerFactoryTest {
    private lateinit var call: Call

    @BeforeEach
    fun setUp() {
        call = mockk(relaxed = true)
        OkHttpSingletons.captureNetworkTimingPhases = true
    }

    @AfterEach
    fun tearDown() {
        OkHttpSingletons.captureNetworkTimingPhases = true
    }

    @Test
    fun `wraps delegate factory and forwards callbacks`() {
        val delegateListener = mockk<EventListener>(relaxed = true)
        val delegateFactory =
            EventListener.Factory {
                delegateListener
            }

        val factory = OkHttpTimingEventListenerFactory.wrap(delegateFactory)
        val listener = factory.create(call)

        listener.callStart(call)

        verify { delegateListener.callStart(call) }
        assertThat(OkHttpCallTimingStore.stateFor(call).callStartNanos).isNotNull()
        OkHttpCallTimingStore.clear()
    }

    @Test
    fun `returns delegate unchanged when timing disabled`() {
        OkHttpSingletons.captureNetworkTimingPhases = false
        val delegateFactory = EventListener.Factory { EventListener.NONE }

        val wrapped = OkHttpTimingEventListenerFactory.wrap(delegateFactory)

        assertThat(wrapped).isSameAs(delegateFactory)
    }

    @Test
    fun `wrap is idempotent`() {
        val delegateFactory = EventListener.Factory { EventListener.NONE }
        val first = OkHttpTimingEventListenerFactory.wrap(delegateFactory)
        val second = OkHttpTimingEventListenerFactory.wrap(first)

        assertThat(second).isSameAs(first)
    }
}
