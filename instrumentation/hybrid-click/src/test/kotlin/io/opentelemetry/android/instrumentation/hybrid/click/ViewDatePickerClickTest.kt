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
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTED_DATE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_GESTURE_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.view.DatePickerSelectionReader
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
 * Covers what the confirm-button tap reports, and — mostly — what it must *not* report.
 *
 * The negative cases carry the weight here. Recognition keys on a tag string, so the risk this suite
 * guards against is misreporting ordinary buttons as date pickers, which would corrupt `tap` counts
 * across every app.
 *
 * The fragment lookup itself (`FragmentManager.findFragment`) is not exercised — it needs real
 * fragment machinery, so it is covered on device instead. What is exercised is everything that
 * decides whether a tap is treated as a date selection at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewDatePickerClickTest {
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

    /**
     * The tagged confirm button reports a date-picker interaction while staying a `button` by
     * control type — the tapped widget really is a button, and nothing keyed on `ui.control.type`
     * should shift because a picker happened to be around it.
     */
    @Test
    fun `tagged confirm button reports a date picker interaction and stays a button`() {
        val window = windowOf(confirmButton())
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(attr(span, ATTR_INTERACTION_TYPE)).isEqualTo("date_picker")
        assertThat(attr(span, ATTR_CONTROL_TYPE)).isEqualTo("button")
        assertThat(attr(span, ATTR_GESTURE_TYPE)).isEqualTo("tap")
    }

    /** An ordinary button is untouched by any of this — the span it produced before is unchanged. */
    @Test
    fun `untagged button still reports an ordinary tap with no date attributes`() {
        val window = windowOf(Button(context).apply { isClickable = true; text = "OK" })
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(attr(span, ATTR_INTERACTION_TYPE)).isEqualTo("tap")
        assertThat(attr(span, ATTR_CONTROL_TYPE)).isEqualTo("button")
        assertThat(selectedDate(span)).isNull()
    }

    /**
     * A picker's Cancel button carries a different tag, so dismissing a date picker must stay an
     * ordinary tap. Otherwise every abandoned picker would inflate date-selection counts.
     */
    @Test
    fun `cancel button reports an ordinary tap`() {
        val cancel = Button(context).apply { isClickable = true; tag = "CANCEL_BUTTON_TAG" }
        val window = windowOf(cancel)
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(attr(span, ATTR_INTERACTION_TYPE)).isEqualTo("tap")
        assertThat(selectedDate(span)).isNull()
    }

    /**
     * The tag alone marks the interaction; the value needs an owning picker. With no fragment to
     * resolve, the span still says `date_picker` but carries no date rather than inventing one.
     */
    @Test
    fun `confirm button with no resolvable picker reports no date`() {
        val window = windowOf(confirmButton())
        generator.startTracking(window)

        tap(window)

        assertThat(selectedDate(singleSpan())).isNull()
    }

    private fun confirmButton(): Button =
        Button(context).apply {
            isClickable = true
            text = "OK"
            tag = DatePickerSelectionReader.CONFIRM_BUTTON_TAG
        }

    private fun windowOf(child: View): Window {
        val root = FrameLayout(context)
        root.addView(child, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
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

    private fun attr(
        span: SpanData,
        key: String,
    ): String? = span.attributes.get(AttributeKey.stringKey(key))

    private fun selectedDate(span: SpanData): Long? = span.attributes.get(AttributeKey.longKey(ATTR_CONTROL_SELECTED_DATE))

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
