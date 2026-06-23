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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
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
 * Verifies toggle-specific behavior of the View detection path: a switch/checkbox reports its
 * sibling title as the widget name (instead of the bare class name) and emits its on/off state as
 * [ATTR_WIDGET_CHECKED].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ViewToggleClickTest {
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
    fun `toggle widget name resolves from its sibling title`() {
        val window = toggleRow(title = "Lock Card Temporarily", checked = false)
        generator.startTracking(window)

        tap(window)

        assertThat(widgetName(singleSpan())).isEqualTo("Lock Card Temporarily")
    }

    @Test
    fun `checked toggle reports checked=true`() {
        val window = toggleRow(title = "Lock Card Temporarily", checked = true)
        generator.startTracking(window)

        tap(window)

        assertThat(checkedState(singleSpan())).isTrue()
    }

    @Test
    fun `unchecked toggle reports checked=false`() {
        val window = toggleRow(title = "Lock Card Temporarily", checked = false)
        generator.startTracking(window)

        tap(window)

        assertThat(checkedState(singleSpan())).isFalse()
    }

    @Test
    fun `non-toggle clickable has no checked attribute`() {
        val button = Button(context).apply { isClickable = true; contentDescription = "Pay" }
        val root = frameRoot(button)
        val window = windowOf(root)
        generator.startTracking(window)

        tap(window)

        assertThat(checkedState(singleSpan())).isNull()
    }

    @Test
    fun `checkable that is not a toggle widget has no checked attribute`() {
        // Mirrors MaterialButton: implements Checkable but is an ordinary button, not a toggle.
        val checkableButton =
            object : Button(context), android.widget.Checkable {
                private var checkedState = false

                override fun setChecked(checked: Boolean) {
                    checkedState = checked
                }

                override fun isChecked(): Boolean = checkedState

                override fun toggle() {
                    checkedState = !checkedState
                }
            }.apply { isClickable = true; contentDescription = "OK" }
        val window = windowOf(frameRoot(checkableButton))
        generator.startTracking(window)

        tap(window)

        assertThat(checkedState(singleSpan())).isNull()
    }

    /** Builds a window whose decorView is a row: [ title-column | checkbox ], both filling it. */
    private fun toggleRow(
        title: String,
        checked: Boolean,
    ): Window {
        val checkBox = CheckBox(context).apply { isClickable = true; isChecked = checked }
        val titleColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title })
            }
        val root = frameRoot(titleColumn, checkBox)
        return windowOf(root)
    }

    /** Stacks [children] in a FrameLayout, each filling it, then measures/lays it out. */
    private fun frameRoot(vararg children: View): FrameLayout {
        val root = FrameLayout(context)
        children.forEach {
            root.addView(it, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        }
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

    private fun singleSpan(): SpanData {
        // Drains the deferred checked-state read (a double post) and the span end, which are all
        // posted with zero delay.
        shadowOf(Looper.getMainLooper()).idle()
        return exporter.finishedSpanItems.single()
    }

    @Test
    fun `checkbox reports checkbox type and button reports button type`() {
        val checkBoxWindow = windowOf(frameRoot(CheckBox(context).apply { isClickable = true }))
        generator.startTracking(checkBoxWindow)
        tap(checkBoxWindow)
        assertThat(widgetType(singleSpan())).isEqualTo("checkbox")

        exporter.reset()
        val buttonWindow = windowOf(frameRoot(Button(context).apply { isClickable = true; text = "Pay" }))
        generator.startTracking(buttonWindow)
        tap(buttonWindow)
        assertThat(widgetType(singleSpan())).isEqualTo("button")
    }

    private fun widgetName(span: SpanData): String? =
        span.attributes.get(AppIncubatingAttributes.APP_WIDGET_NAME)

    private fun widgetType(span: SpanData): String? =
        span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("app.widget.type"))

    private fun checkedState(span: SpanData): Boolean? =
        span.attributes.get(AttributeKey.booleanKey(ATTR_WIDGET_CHECKED))

    private companion object {
        const val VIEW_SIZE = 500
        const val TAP_X = 10f
        const val TAP_Y = 10f
    }
}
