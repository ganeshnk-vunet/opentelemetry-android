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
import io.mockk.every
import io.mockk.mockk
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
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ClickInteractionTraceTest {
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
        generator =
            ClickEventGenerator(
                tracer = sdk.getTracer("test"),
                activeContextWindowMillis = 0,
            )
    }

    @Test
    fun `each click starts a new interaction trace`() {
        val window = windowWith(label = "Pay")
        generator.startTracking(window)

        tap(window)
        tap(window)

        val clickSpans = finishedSpans().filter { it.name == "ui.interaction" }
        assertThat(clickSpans).hasSize(2)
        assertThat(clickSpans[0].traceId).isNotEqualTo(clickSpans[1].traceId)
    }

    @Test
    fun `click does not inherit an existing app start trace`() {
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()
        val tracer = sdk.getTracer("test")
        val appStart =
            tracer
                .spanBuilder("app.start")
                .setNoParent()
                .startSpan()
        appStart.makeCurrent().use {
            val window = windowWith(label = "Pay")
            generator.startTracking(window)
            tap(window)
        }
        appStart.end()

        val clickSpan = finishedSpans().single { it.name == "ui.interaction" }
        val appStartSpan = finishedSpans().single { it.name == "app.start" }
        assertThat(clickSpan.traceId).isNotEqualTo(appStartSpan.traceId)
    }

    private fun windowWith(label: String): Window {
        val button =
            Button(context).apply {
                isClickable = true
                contentDescription = label
            }
        val root =
            FrameLayout(context).apply {
                addView(
                    button,
                    FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE),
                )
            }
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)

        return mockk<Window>(relaxed = true).also { window ->
            every { window.decorView } returns root
        }
    }

    private fun tap(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP))
    }

    private fun motion(action: Int): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, TAP_X, TAP_Y, 0)

    private fun finishedSpans(): List<SpanData> {
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems
    }

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
