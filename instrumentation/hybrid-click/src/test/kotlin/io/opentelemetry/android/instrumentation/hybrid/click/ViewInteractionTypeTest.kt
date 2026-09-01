/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that the emitted `ui.interaction` span reports which gesture produced it, and that the
 * kind is decided by how long the pointer was held.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewInteractionTypeTest {
    private lateinit var context: Context
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var generator: ClickEventGenerator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        exporter = InMemorySpanExporter.create()
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()
        generator = ClickEventGenerator(tracer = sdk.getTracer("test"), activeContextWindowMillis = 0)
    }

    @Test
    fun `quick press reports a tap`() {
        val window = buttonWindow()
        generator.startTracking(window)

        press(window, holdMs = 50L)

        assertThat(interactionType(singleSpan())).isEqualTo("tap")
    }

    @Test
    fun `press held past the long-press timeout reports a long press`() {
        val window = buttonWindow()
        generator.startTracking(window)

        press(window, holdMs = 900L)

        assertThat(interactionType(singleSpan())).isEqualTo("long_press")
    }

    /** A press that leaves the touch slop is not a tap, so it emits nothing regardless of duration. */
    @Test
    fun `slow drag emits no span`() {
        val window = buttonWindow()
        generator.startTracking(window)

        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L))
        generator.generateClick(window, motion(MotionEvent.ACTION_MOVE, 400L, x = TAP_X + 100f))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 900L, x = TAP_X + 100f))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    private fun buttonWindow(): Window {
        val button = Button(context).apply { isClickable = true; contentDescription = "Pay" }
        val root = FrameLayout(context)
        root.addView(button, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        return mockk<Window>(relaxed = true).also { every { it.decorView } returns root }
    }

    private fun press(
        window: Window,
        holdMs: Long,
    ) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, holdMs))
    }

    private fun motion(
        action: Int,
        eventTimeMs: Long,
        x: Float = TAP_X,
    ): MotionEvent = MotionEvent.obtain(0L, eventTimeMs, action, x, TAP_Y, 0)

    private fun singleSpan(): SpanData {
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems.single()
    }

    private fun interactionType(span: SpanData): String? =
        span.attributes.get(AttributeKey.stringKey(ATTR_INTERACTION_TYPE))

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
