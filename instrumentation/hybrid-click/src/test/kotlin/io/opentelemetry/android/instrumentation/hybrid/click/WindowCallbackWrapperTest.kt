/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.view.MotionEvent
import android.view.Window
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowCallbackWrapperTest {
    /**
     * Locks in the production-safety guarantee: a failure while deriving telemetry must never stop
     * the touch from reaching the real callback.
     */
    @Test
    fun `forwards the touch to the delegate even when click handling throws`() {
        val callback = mockk<Window.Callback>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        val generator = mockk<ClickEventGenerator>()
        val event = mockk<MotionEvent>(relaxed = true)
        every { generator.generateClick(window, event) } throws RuntimeException("boom")
        every { callback.dispatchTouchEvent(event) } returns true

        val wrapper = WindowCallbackWrapper(callback, window, generator)
        val result = wrapper.dispatchTouchEvent(event)

        assertThat(result).isTrue()
        verify(exactly = 1) { callback.dispatchTouchEvent(event) }
    }
}
