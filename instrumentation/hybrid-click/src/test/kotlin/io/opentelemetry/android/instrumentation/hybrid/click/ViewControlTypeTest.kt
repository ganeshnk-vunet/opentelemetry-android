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
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTION_MODE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
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
 * Verifies `ui.control.type` and `ui.control.selection_mode` on real emitted spans, not just the
 * pure [io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveSelectionMode]
 * resolver — end to end through [ClickEventGenerator], the same as the checked-state and
 * interaction-type coverage.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewControlTypeTest {
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
    fun `button reports control type but no selection mode`() {
        val window = clickableWindow(Button(context).apply { isClickable = true; contentDescription = "Pay" })
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(controlType(span)).isEqualTo("button")
        assertThat(selectionMode(span)).isNull()
    }

    @Test
    fun `checkbox reports control type and multiple selection mode`() {
        val window = clickableWindow(CheckBox(context).apply { isClickable = true; contentDescription = "Remember me" })
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(controlType(span)).isEqualTo("checkbox")
        assertThat(selectionMode(span)).isEqualTo("multiple")
    }

    private fun clickableWindow(view: View): Window {
        val root = FrameLayout(context)
        root.addView(view, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        return mockk<Window>(relaxed = true).also { every { it.decorView } returns root }
    }

    private fun tap(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 50L))
    }

    private fun motion(
        action: Int,
        eventTimeMs: Long,
    ): MotionEvent = MotionEvent.obtain(0L, eventTimeMs, action, TAP_X, TAP_Y, 0)

    private fun singleSpan(): SpanData {
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems.single()
    }

    private fun controlType(span: SpanData): String? = span.attributes.get(AttributeKey.stringKey(ATTR_CONTROL_TYPE))

    private fun selectionMode(span: SpanData): String? =
        span.attributes.get(AttributeKey.stringKey(ATTR_CONTROL_SELECTION_MODE))

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
