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

class OkHttpCallTimingStoreTest {
    private lateinit var call: Call

    @BeforeEach
    fun setUp() {
        call = mockk(relaxed = true)
        OkHttpCallTimingStore.clear()
    }

    @Test
    fun `remove clears stored call`() {
        OkHttpCallTimingStore.stateFor(call).callStartNanos = 0L
        OkHttpCallTimingStore.stateFor(call).callEndNanos = 5_000_000L

        val timing = OkHttpCallTimingStore.remove(call)

        assertThat(timing?.totalMs).isEqualTo(5L)
        assertThat(OkHttpCallTimingStore.remove(call)).isNull()
    }
}
