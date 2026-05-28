/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for TTID (Time to Initial Display) capture in [AppStartupTimer].
 *
 * TTID is recorded via [ViewTreeObserver.OnDrawListener] + [View.post] so that the timestamp
 * falls after the first frame is committed to SurfaceFlinger.
 *
 * To avoid dependence on [ViewTreeObserver] internals, the Activity/Window/View chain is
 * mocked: [View.post] executes the [Runnable] inline (synchronously), which means no looper
 * draining is needed and tests are straightforward to reason about.
 *
 * [FakeActivity] is a local subclass so MockK's ByteBuddy proxy is named in OUR package
 * (e.g. `...FakeActivity$ByteBuddy$...`) rather than `android.app.Activity$ByteBuddy$...`,
 * which would otherwise be caught by the system-activity class-name filter in
 * [AppStartupTimer.createLifecycleCallback].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AppStartupTimerTtidTest {

    /** Local Activity subclass whose ByteBuddy proxy name will NOT start with "android.". */
    private class FakeActivity : Activity()
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var sdk: OpenTelemetrySdk

    @Before
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()
    }

    @Test
    fun `ttid event is emitted after first frame draw`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        val listenerSlot = attachTtidListener(timer)
        listenerSlot.captured.onDraw()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        assertThat(appStart.events.map { it.name }).contains(AppStartupTimer.EVENT_TTID)
    }

    @Test
    fun `span ends when ttid fires`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        val listenerSlot = attachTtidListener(timer)
        listenerSlot.captured.onDraw()

        assertThat(exporter.finishedSpanItems).hasSize(1)
    }

    @Test
    fun `end is no-op while ttid listener is attached but not yet fired`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        attachTtidListener(timer)  // attaches listener but does not fire onDraw()

        // draw listener has been attached; end() must yield to it
        timer.end()

        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    @Test
    fun `ttid event is emitted only once even if draw fires multiple times`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        val listenerSlot = attachTtidListener(timer)
        listenerSlot.captured.onDraw()
        // second call — AtomicBoolean guard in the posted Runnable prevents a duplicate
        listenerSlot.captured.onDraw()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        assertThat(appStart.events.filter { it.name == AppStartupTimer.EVENT_TTID }).hasSize(1)
    }

    @Test
    fun `without ttid listener end ends the span normally`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        // No onActivityResumed → no draw listener → end() ends the span immediately
        timer.end()

        assertThat(exporter.finishedSpanItems).hasSize(1)
        assertThat(exporter.finishedSpanItems[0].name).isEqualTo(RumConstants.APP_START_SPAN_NAME)
    }

    @Test
    fun `onActivityResumed after span is cleared is a no-op`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())
        timer.end()  // clears startupSpan

        // startupSpan is null → onActivityResumed returns immediately, must not throw
        val activity = mockk<FakeActivity>(relaxed = true)
        timer.createLifecycleCallback().onActivityResumed(activity)

        assertThat(exporter.finishedSpanItems).hasSize(1)
    }

    /**
     * Calls [AppStartupTimer.createLifecycleCallback].onActivityResumed() with a mocked
     * Activity/Window/View chain. [View.post] is stubbed to execute the [Runnable] inline
     * (synchronously) so no looper draining is needed.
     *
     * @return the [CapturingSlot] holding the [ViewTreeObserver.OnDrawListener] registered by
     *   [AppStartupTimer]; call [ViewTreeObserver.OnDrawListener.onDraw] on it to simulate a draw.
     */
    private fun attachTtidListener(timer: AppStartupTimer): CapturingSlot<ViewTreeObserver.OnDrawListener> {
        val listenerSlot = slot<ViewTreeObserver.OnDrawListener>()
        val vto =
            mockk<ViewTreeObserver>(relaxed = true) {
                every { addOnDrawListener(capture(listenerSlot)) } just runs
            }
        val decorView =
            mockk<View>(relaxed = true) {
                every { viewTreeObserver } returns vto
                // Run the Runnable inline — no looper needed
                every { post(any()) } answers { firstArg<Runnable>().run(); true }
            }
        val window =
            mockk<Window>(relaxed = true) {
                every { this@mockk.decorView } returns decorView
            }
        val activity =
            mockk<FakeActivity>(relaxed = true) {
                every { this@mockk.window } returns window
            }
        timer.createLifecycleCallback().onActivityResumed(activity)
        return listenerSlot
    }
}
