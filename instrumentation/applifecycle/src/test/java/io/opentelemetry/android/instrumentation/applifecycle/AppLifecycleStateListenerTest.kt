/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
internal class AppLifecycleStateListenerTest {
    @MockK(relaxed = true)
    private lateinit var emitter: AppLifecycleSpanEmitter

    private lateinit var underTest: AppLifecycleStateListener

    @BeforeEach
    fun init() {
        underTest = AppLifecycleStateListener(emitter)
    }

    @Test
    fun `onApplicationForegrounded emits a foreground span`() {
        underTest.onApplicationForegrounded()

        verify(exactly = 1) { emitter.emitForeground() }
        verify(exactly = 0) { emitter.emitBackground() }
    }

    @Test
    fun `onApplicationBackgrounded emits a background span`() {
        underTest.onApplicationBackgrounded()

        verify(exactly = 1) { emitter.emitBackground() }
        verify(exactly = 0) { emitter.emitForeground() }
    }
}
