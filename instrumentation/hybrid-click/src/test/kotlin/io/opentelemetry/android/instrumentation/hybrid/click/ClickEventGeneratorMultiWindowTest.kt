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
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Verifies that [ClickEventGenerator] tracks multiple windows simultaneously (an Activity window
 * plus dialog windows stacked on top) with independent, per-window gesture state — the behavior
 * that makes taps inside dialogs produce `ui.interaction` spans.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ClickEventGeneratorMultiWindowTest {
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
                // End the span immediately on the next main-loop idle so the exporter sees it.
                activeContextWindowMillis = 0,
            )
    }

    @Test
    fun `tap on a tracked window emits a ui_interaction span resolved against that window`() {
        val window = windowWith(label = "A")
        generator.startTracking(window)

        tap(window)

        val spans = finishedSpans()
        assertThat(spans).hasSize(1)
        // Asserted as a literal on purpose: this pins the exported wire value, so a change to
        // RumConstants.UI_INTERACTION_SPAN_NAME fails here instead of passing silently.
        assertThat(spans.single().name).isEqualTo("ui.interaction")
        assertThat(widgetName(spans.single())).isEqualTo("A")
    }

    @Test
    fun `two windows are tracked independently and each resolves against its own decorView`() {
        val windowA = windowWith(label = "A")
        val windowB = windowWith(label = "B")
        generator.startTracking(windowA)
        generator.startTracking(windowB)

        tap(windowA)
        tap(windowB)

        val labels = finishedSpans().map { widgetName(it) }
        assertThat(labels).containsExactlyInAnyOrder("A", "B")
    }

    @Test
    fun `gesture state does not leak across windows`() {
        val windowA = windowWith(label = "A")
        val windowB = windowWith(label = "B")
        generator.startTracking(windowA)
        generator.startTracking(windowB)

        // A down followed by a B up must not qualify a tap on B: B never saw the down.
        generator.generateClick(windowA, motion(MotionEvent.ACTION_DOWN))
        generator.generateClick(windowB, motion(MotionEvent.ACTION_UP))

        assertThat(finishedSpans()).isEmpty()
    }

    @Test
    fun `stopping one window leaves the other tracking`() {
        val windowA = windowWith(label = "A")
        val windowB = windowWith(label = "B")
        generator.startTracking(windowA)
        generator.startTracking(windowB)

        generator.stopTracking(windowA)

        tap(windowA)
        tap(windowB)

        val labels = finishedSpans().map { widgetName(it) }
        assertThat(labels).containsExactly("B")
    }

    @Test
    fun `tap on an untracked window emits nothing`() {
        val window = windowWith(label = "A")

        tap(window)

        assertThat(finishedSpans()).isEmpty()
    }

    /** Builds a mock [Window] whose real decorView contains a single clickable, labeled button. */
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

    /** Feeds a qualified tap (down then up at the same point) for [window]. */
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

    private fun widgetName(span: SpanData): String? =
        span.attributes.get(AppIncubatingAttributes.APP_WIDGET_NAME)

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
