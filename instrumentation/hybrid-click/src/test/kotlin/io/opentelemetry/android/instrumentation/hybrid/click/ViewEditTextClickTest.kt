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
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**
 * Verifies that tapping an editable text field is captured by its label and never leaks the typed
 * content (which may be PII or a password).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewEditTextClickTest {
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
    fun `edit text uses its hint as the label, never the typed text`() {
        val window =
            windowOf(
                EditText(context).apply {
                    isClickable = true
                    hint = "Mobile Number"
                    setText("9876543210")
                },
            )
        generator.startTracking(window)

        tap(window)

        assertThat(widgetName(singleSpan())).isEqualTo("Mobile Number")
    }

    @Test
    fun `edit text prefers content description over hint`() {
        val window =
            windowOf(
                EditText(context).apply {
                    isClickable = true
                    contentDescription = "Password field"
                    hint = "Password"
                    setText("hunter2")
                },
            )
        generator.startTracking(window)

        tap(window)

        val name = widgetName(singleSpan())
        assertThat(name).isEqualTo("Password field")
        assertThat(name).doesNotContain("hunter2")
    }

    @Test
    fun `default edit text without explicit clickable is still detected`() {
        // Real-world EditText defaults to focusable, not clickable; the detector must still find it.
        val window =
            windowOf(
                EditText(context).apply {
                    hint = "Mobile Number"
                    setText("9876543210")
                },
            )
        generator.startTracking(window)

        tap(window)

        assertThat(widgetName(singleSpan())).isEqualTo("Mobile Number")
    }

    private fun windowOf(field: View): Window {
        val root = FrameLayout(context).apply { addView(field, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE)) }
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        return mockk<Window>(relaxed = true).also { every { it.decorView } returns root }
    }

    private fun tap(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP))
    }

    private fun motion(action: Int): MotionEvent = MotionEvent.obtain(0L, 0L, action, TAP, TAP, 0)

    private fun singleSpan(): SpanData {
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems.single()
    }

    private fun widgetName(span: SpanData): String? =
        span.attributes.get(AppIncubatingAttributes.APP_WIDGET_NAME)

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP = 10f
    }
}
