/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.mockk
import okhttp3.Call
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OkHttpTimingEventListenerTest {
    private lateinit var listener: OkHttpTimingEventListener
    private lateinit var call: Call

    @BeforeEach
    fun setUp() {
        listener = OkHttpTimingEventListener()
        call = mockk(relaxed = true)
        OkHttpCallTimingStore.clear()
    }

    @Test
    fun `records call failure as incomplete`() {
        listener.callStart(call)
        listener.callFailed(call, java.io.IOException("boom"))

        val timing = OkHttpCallTimingStore.remove(call)

        assertThat(timing?.phasesComplete).isFalse()
    }
}
