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
import android.widget.ProgressBar
import android.widget.RatingBar
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_VALUE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_GESTURE_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ControlValue
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTarget
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SLIDER
import io.opentelemetry.android.instrumentation.hybrid.click.view.ViewTapTargetDetector
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
 * Covers range controls, which produced no telemetry at all before slider support: a `SeekBar` is
 * not clickable, so the detector rejected it outright — not just for drags but for taps too.
 *
 * The drag cases are the reason the gesture classifier reports [
 * io.opentelemetry.android.instrumentation.hybrid.click.shared.GestureType.DRAG] instead of
 * discarding movement, and the percentage case is the reason the value is normalized rather than
 * raw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewSliderClickTest {
    private lateinit var context: Context
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var tracer: io.opentelemetry.api.trace.Tracer
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
        tracer = sdk.getTracer("test")
        generator = ClickEventGenerator(tracer = tracer, activeContextWindowMillis = 0)
    }

    /**
     * Locks the framework fact the whole `isSeekBar` branch exists for. If a future framework or
     * androidx change ever makes `SeekBar` clickable by default, this fails and says so, rather than
     * leaving that branch silently dead.
     */
    @Test
    fun `seek bar is not clickable by default`() {
        assertThat(SeekBar(context).isClickable).isFalse()
    }

    @Test
    fun `tap on a seek bar emits a slider span`() {
        val window = sliderWindow(progress = 50, max = 200)
        generator.startTracking(window)

        tap(window)

        val span = singleSpan()
        assertThat(attr(span, ATTR_CONTROL_TYPE)).isEqualTo("slider")
        assertThat(attr(span, ATTR_INTERACTION_TYPE)).isEqualTo("slider")
        assertThat(attr(span, ATTR_GESTURE_TYPE)).isEqualTo("tap")
    }

    @Test
    fun `drag on a seek bar emits a slider span carrying the drag gesture`() {
        val window = sliderWindow(progress = 50, max = 200)
        generator.startTracking(window)

        drag(window)

        val span = singleSpan()
        assertThat(attr(span, ATTR_INTERACTION_TYPE)).isEqualTo("slider")
        assertThat(attr(span, ATTR_GESTURE_TYPE)).isEqualTo("drag")
    }

    /**
     * The non-vacuous check on normalization. `max` is deliberately 200 so a raw-value regression
     * reports `50.0` and fails here; with the default `max` of 100 the two would be identical and
     * this test would pass either way.
     */
    @Test
    fun `slider value is a percentage of the control range, not the raw value`() {
        val window = sliderWindow(progress = 50, max = 200)
        generator.startTracking(window)

        tap(window)

        assertThat(percent(singleSpan())).isEqualTo(25.0)
    }

    /** A `ProgressBar` is an indicator, not a control, and must stay invisible to this module. */
    @Test
    fun `progress bar emits no span`() {
        val window = windowOf(ProgressBar(context))
        generator.startTracking(window)

        drag(window)
        tap(window)

        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    /**
     * An indicator `RatingBar` refuses touches at the framework level (`setIsIndicator` clears
     * `mIsUserSeekable`), so it is as inert as a `ProgressBar` despite extending `AbsSeekBar`.
     */
    @Test
    fun `indicator rating bar emits no span`() {
        val window = windowOf(RatingBar(context).apply { setIsIndicator(true) })
        generator.startTracking(window)

        drag(window)
        tap(window)

        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    /** Dragging over anything that is not a range control stays unreported, as before. */
    @Test
    fun `drag over a button emits no span`() {
        val window = windowOf(Button(context).apply { isClickable = true })
        generator.startTracking(window)

        drag(window)

        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    /**
     * A pre-gesture value must be dropped for a tap and kept for a drag.
     *
     * This models the Compose path, whose value can only be snapshotted *before* the gesture is
     * delivered: a Compose control's value reaches its semantics after a composition pass, on a
     * frame boundary rather than a message boundary, so a deferred read cannot reliably see it. That
     * snapshot trails a drag by at most one frame, but for a tap-seek it is the pre-tap value
     * outright — reporting it would put a number on the wire that the user never selected.
     *
     * Driven through an injected detector rather than real Compose, because the Compose detector is
     * reached over a reflection bridge that unit tests cannot construct.
     */
    @Test
    fun `a pre-gesture value is recorded for a drag but not for a tap`() {
        val detector = mockk<ViewTapTargetDetector>()
        every { detector.findTapTarget(any(), any(), any()) } returns preGestureSliderTarget()
        val generator = ClickEventGenerator(
            tracer = tracer,
            viewTapTargetDetector = detector,
            activeContextWindowMillis = 0,
        )
        val window = windowOf(Button(context))
        generator.startTracking(window)

        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L, TAP_X))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 50L, TAP_X))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(percent(exporter.finishedSpanItems.single())).isNull()

        exporter.reset()
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 100L, TAP_X))
        generator.generateClick(window, motion(MotionEvent.ACTION_MOVE, 200L, TAP_X + DRAG_DISTANCE))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 300L, TAP_X + DRAG_DISTANCE))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(percent(exporter.finishedSpanItems.single())).isEqualTo(42.0)
    }

    private fun preGestureSliderTarget(): TapTarget =
        TapTarget(
            source = "compose",
            widgetId = "1",
            widgetName = "Amount",
            label = "Amount",
            x = 0L,
            y = 0L,
            type = WIDGET_TYPE_SLIDER,
            isTracking = true,
            valueProvider = { ControlValue.Percentage(42.0) },
            valueIsPreGesture = true,
        )

    private fun sliderWindow(
        progress: Int,
        max: Int,
    ): Window =
        windowOf(
            SeekBar(context).apply {
                this.max = max
                this.progress = progress
                // The emitter requires the control to be actively tracking the gesture, which the
                // framework sets in startDrag. These harnesses call generateClick directly without
                // dispatching to the widget, so it never runs -- set it as the widget would.
                isPressed = true
            },
        )

    private fun windowOf(child: View): Window {
        val root = FrameLayout(context)
        root.addView(child, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        return mockk<Window>(relaxed = true).also { every { it.decorView } returns root }
    }

    private fun tap(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L, TAP_X))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 50L, TAP_X))
    }

    /** Moves well beyond the touch slop, so the classifier reports a drag. */
    private fun drag(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN, 0L, TAP_X))
        generator.generateClick(window, motion(MotionEvent.ACTION_MOVE, 100L, TAP_X + DRAG_DISTANCE))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP, 200L, TAP_X + DRAG_DISTANCE))
    }

    private fun motion(
        action: Int,
        eventTimeMs: Long,
        x: Float,
    ): MotionEvent = MotionEvent.obtain(0L, eventTimeMs, action, x, TAP_Y, 0)

    private fun singleSpan(): SpanData {
        // Drains the deferred value read (a double post) and the span end, all posted with no delay.
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems.single()
    }

    private fun attr(
        span: SpanData,
        key: String,
    ): String? = span.attributes.get(AttributeKey.stringKey(key))

    private fun percent(span: SpanData): Double? = span.attributes.get(AttributeKey.doubleKey(ATTR_CONTROL_VALUE))

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
        const val DRAG_DISTANCE = 200f
    }
}
