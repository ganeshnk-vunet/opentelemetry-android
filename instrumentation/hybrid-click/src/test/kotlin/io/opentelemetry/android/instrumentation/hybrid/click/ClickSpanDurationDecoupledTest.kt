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
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ClickSpanDurationDecoupledTest {
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
        generator =
            ClickEventGenerator(
                tracer = tracer,
                activeContextWindowMillis = ACTIVE_CONTEXT_WINDOW_MILLIS,
            )
    }

    @After
    fun tearDown() {
        ActiveInteractionContext.clear()
        exporter.reset()
    }

    @Test
    fun `non-toggle click span ends before active context window expires`() {
        val window = windowWith(label = "Pay")
        generator.startTracking(window)

        tap(window)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)

        assertThat(exporter.finishedSpanItems).hasSize(1)
        assertThat(exporter.finishedSpanItems.single().name).isEqualTo("ui.interaction")
    }

    @Test
    fun `downstream span within window parents to click`() {
        val window = windowWith(label = "Pay")
        generator.startTracking(window)

        tap(window)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)

        val clickSpan = exporter.finishedSpanItems.single { it.name == "ui.interaction" }
        val parentContext = ActiveInteractionContext.parentContextOr(io.opentelemetry.context.Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.single { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(clickSpan.spanContext.spanId)
        assertThat(childSpan.traceId).isEqualTo(clickSpan.traceId)
    }

    @Test
    fun `downstream span after window does not parent to click`() {
        val window = windowWith(label = "Pay")
        generator.startTracking(window)

        tap(window)
        shadowOf(Looper.getMainLooper()).idleFor(ACTIVE_CONTEXT_WINDOW_MILLIS + 50, TimeUnit.MILLISECONDS)

        val clickSpan = exporter.finishedSpanItems.single { it.name == "ui.interaction" }
        val parentContext = ActiveInteractionContext.parentContextOr(io.opentelemetry.context.Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.single { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isNotEqualTo(clickSpan.spanContext.spanId)
    }

    @Test
    fun `toggle click span ends after checked-state read not after window`() {
        val checkBox =
            CheckBox(context).apply {
                isClickable = true
                isChecked = false
            }
        val root = frameRoot(checkBox)
        val window = windowOf(root)
        generator.startTracking(window)

        tap(window)
        shadowOf(Looper.getMainLooper()).idle()

        val clickSpan = exporter.finishedSpanItems.single()
        assertThat(clickSpan.name).isEqualTo("ui.interaction")
        assertThat(checkedState(clickSpan)).isFalse()
    }

    private fun windowWith(label: String): Window {
        val button =
            Button(context).apply {
                isClickable = true
                contentDescription = label
            }
        return windowOf(frameRoot(button))
    }

    private fun frameRoot(child: View): FrameLayout {
        val root = FrameLayout(context)
        root.addView(child, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        val spec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        return root
    }

    private fun windowOf(root: View): Window =
        mockk<Window>(relaxed = true).also { every { it.decorView } returns root }

    private fun tap(window: Window) {
        generator.generateClick(window, motion(MotionEvent.ACTION_DOWN))
        generator.generateClick(window, motion(MotionEvent.ACTION_UP))
    }

    private fun motion(action: Int): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, TAP_X, TAP_Y, 0)

    private fun checkedState(span: SpanData): Boolean? =
        span.attributes.get(AttributeKey.booleanKey(ATTR_WIDGET_CHECKED))

    private companion object {
        const val ACTIVE_CONTEXT_WINDOW_MILLIS = 200L
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
