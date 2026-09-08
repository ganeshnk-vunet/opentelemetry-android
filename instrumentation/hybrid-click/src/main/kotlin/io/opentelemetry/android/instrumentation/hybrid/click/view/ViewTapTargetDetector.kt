/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.view

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ControlValue
import io.opentelemetry.android.instrumentation.hybrid.click.shared.LabelResolver
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SOURCE_VIEW
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTarget
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTargetDetector
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_BUTTON
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_CHECKBOX
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_IMAGE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_RADIO
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SLIDER
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SWITCH
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TEXT
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TEXT_FIELD
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TOGGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_VIEW
import java.util.LinkedList
import kotlin.math.round

internal class ViewTapTargetDetector : TapTargetDetector {
    private val viewCoordinates = IntArray(2)

    override fun findTapTarget(
        rootView: View,
        x: Float,
        y: Float,
    ): TapTarget? {
        val queue = LinkedList<View>()
        queue.addFirst(rootView)
        var target: View? = null

        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (isJetpackComposeView(view)) {
                return null
            }

            if (isValidClickTarget(view)) {
                target = view
            }

            if (view is ViewGroup) {
                handleViewGroup(view, x, y, queue)
            }
        }

        val clickTarget = target ?: return null
        return TapTarget(
            source = SOURCE_VIEW,
            widgetId = clickTarget.id.toString(),
            widgetName = viewToName(clickTarget),
            label = viewToLabel(clickTarget),
            x = clickTarget.x.toLong(),
            y = clickTarget.y.toLong(),
            type = viewToType(clickTarget),
            isTracking = clickTarget.isPressed,
            valueProvider = valueProviderOf(clickTarget),
        )
    }

    /** Maps a tapped View to a normalized widget kind. */
    private fun viewToType(view: View): String =
        when {
            view is EditText -> WIDGET_TYPE_TEXT_FIELD
            view is CompoundButton -> compoundButtonType(view)
            isSeekBar(view) || isMaterialSlider(view) -> WIDGET_TYPE_SLIDER
            view is CheckedTextView -> WIDGET_TYPE_CHECKBOX
            view is ImageButton -> WIDGET_TYPE_BUTTON
            view is Button -> WIDGET_TYPE_BUTTON
            view is ImageView -> WIDGET_TYPE_IMAGE
            view is TextView -> WIDGET_TYPE_TEXT
            else -> WIDGET_TYPE_VIEW
        }

    /**
     * Whether [view] is a user-seekable range control from the framework.
     *
     * Matched on [AbsSeekBar], not `SeekBar`: `RatingBar` extends [AbsSeekBar] directly, so keying
     * on `SeekBar` would miss it. `ProgressBar` is [AbsSeekBar]'s *superclass* and is deliberately
     * excluded — it is a non-interactive indicator, not a control. An indicator `RatingBar` is
     * excluded on exactly that reasoning: `setIsIndicator` clears `mIsUserSeekable`, after which
     * `AbsSeekBar.onTouchEvent` refuses the touch outright, making it as inert as a `ProgressBar`.
     */
    private fun isSeekBar(view: View): Boolean = view is AbsSeekBar && !(view is RatingBar && view.isIndicator)

    /**
     * Whether [view] is a Material slider, matched by walking superclasses for the qualified name of
     * `BaseSlider`.
     *
     * By name because this module must not depend on `com.google.android.material` — the same
     * constraint that makes [compoundButtonType] match `SwitchCompat`/`MaterialSwitch` by name. The
     * base class rather than `Slider` covers `Slider`, `RangeSlider` and any app subclass in one
     * constant, and the *qualified* name rather than a `simpleName.contains("Slider")` check avoids
     * claiming unrelated classes that merely have "Slider" in their name.
     *
     * Note a Material slider is already `setClickable(true)` in its constructor, so it reached this
     * detector before this kind existed — as a plain `view`. A `SeekBar` is not clickable and so
     * reached it not at all.
     */
    private fun isMaterialSlider(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == CLASS_NAME_MATERIAL_BASE_SLIDER) {
                return true
            }
            type = type.superclass
        }
        return false
    }

    private fun compoundButtonType(view: CompoundButton): String =
        when {
            view is CheckBox -> WIDGET_TYPE_CHECKBOX
            view is RadioButton -> WIDGET_TYPE_RADIO
            view is ToggleButton -> WIDGET_TYPE_TOGGLE
            // android.widget.Switch is matched by type; SwitchCompat / MaterialSwitch are matched by
            // name to avoid pulling appcompat/material into this module.
            view is Switch || view.javaClass.simpleName.contains("Switch", ignoreCase = true) -> WIDGET_TYPE_SWITCH
            // Intentional: any other CompoundButton (incl. custom/third-party ones) is a toggleable
            // control, so it is reported as "toggle" rather than "unknown".
            else -> WIDGET_TYPE_TOGGLE
        }

    /**
     * Returns a live value reader, but only for widgets that genuinely carry a value.
     *
     * For toggles we intentionally do not key off the [android.widget.Checkable] interface:
     * `MaterialButton` implements it while being an ordinary (non-toggle) button, which would
     * otherwise tag every Material button — e.g. a dialog's "OK" — with `checked=false`.
     */
    private fun valueProviderOf(view: View): (() -> ControlValue?)? =
        when {
            view is CompoundButton -> ({ ControlValue.Checked(view.isChecked) })
            view is CheckedTextView -> ({ ControlValue.Checked(view.isChecked) })
            isSeekBar(view) -> ({ seekBarPercent(view as AbsSeekBar)?.let(ControlValue::Percentage) })
            isMaterialSlider(view) -> ({ materialSliderPercent(view)?.let(ControlValue::Percentage) })
            else -> null
        }

    /**
     * Position of [view] as a percentage of its own range.
     *
     * `min` is treated as `0` rather than read from `getMin()`, which is API 26 while this module's
     * `minSdk` is 23 — an unguarded call fails AnimalSniffer, and Android lint would not catch it
     * because `NewApi` is disabled for these modules. `ProgressBar` initializes `mMin = 0` and
     * offered no API to change it before 26, so this is only inexact for an app that sets
     * `android:min` on API 26+, which is worth far less than the guard it costs.
     */
    private fun seekBarPercent(view: AbsSeekBar): Double? = percentOf(view.progress.toDouble(), 0.0, view.max.toDouble())

    /**
     * Position of a Material slider as a percentage of its own range, read reflectively for the same
     * reason [isMaterialSlider] matches by name.
     *
     * A `RangeSlider` has no `getValue()` — only `getValues()` — so resolution fails and no value
     * attribute is emitted, which is correct: a range slider has no single position to report. The
     * span is still emitted with `ui.control.type = slider`.
     */
    private fun materialSliderPercent(view: View): Double? =
        runCatching {
            val type = view.javaClass
            val value = (type.getMethod("getValue").invoke(view) as Number).toDouble()
            val from = (type.getMethod("getValueFrom").invoke(view) as Number).toDouble()
            val to = (type.getMethod("getValueTo").invoke(view) as Number).toDouble()
            percentOf(value, from, to)
        }.getOrNull()

    /** Scales [value] to 0–100 across [from]..[to], or `null` for a degenerate range. */
    private fun percentOf(
        value: Double,
        from: Double,
        to: Double,
    ): Double? {
        val range = to - from
        if (range <= 0.0) {
            return null
        }
        val percent = (value - from) / range * 100.0
        return round(percent.coerceIn(0.0, 100.0) * 100.0) / 100.0
    }

    private fun isToggle(view: View): Boolean = view is CompoundButton || view is CheckedTextView

    private fun isValidClickTarget(view: View): Boolean =
        view.isVisible && (view.isClickable || view is EditText || isSeekBar(view))

    private fun isPasswordField(view: EditText): Boolean {
        val variation = view.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun handleViewGroup(
        view: ViewGroup,
        x: Float,
        y: Float,
        stack: LinkedList<View>,
    ) {
        if (!view.isVisible) return

        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (hitTest(child, x, y) && !isJetpackComposeView(child)) {
                stack.add(child)
            }
        }
    }

    private fun hitTest(
        view: View,
        x: Float,
        y: Float,
    ): Boolean {
        view.getLocationInWindow(viewCoordinates)
        val vx = viewCoordinates[0]
        val vy = viewCoordinates[1]

        val w = view.width
        val h = view.height
        return !(x < vx || x > vx + w || y < vy || y > vy + h)
    }

    private fun viewToName(view: View): String =
        try {
            view.resources?.getResourceEntryName(view.id) ?: view.id.toString()
        } catch (_: Throwable) {
            view.id.toString()
        }

    private fun viewToLabel(view: View): String {
        val contentDescription = view.contentDescription?.toString()

        // Editable text fields must never expose their typed content (it may be PII or a password).
        // Resolve from the accessibility label, then the hint, then the class name. Password fields
        // fall back to a constant rather than the class name, mirroring the Compose path.
        if (view is EditText) {
            if (isPasswordField(view)) {
                return contentDescription?.takeIf { it.isNotBlank() }
                    ?: view.hint?.toString()?.takeIf { it.isNotBlank() }
                    ?: PASSWORD_FIELD_LABEL
            }
            return LabelResolver.resolve(
                contentDescription = contentDescription,
                text = view.hint?.toString(),
                className = view.javaClass.simpleName,
                fallback = view.id.toString(),
            )
        }

        val text = (view as? android.widget.TextView)?.text?.toString()

        val resolvedLabel =
            if (contentDescription.isNullOrBlank() && text.isNullOrBlank()) {
                // When the clicked target carries no label of its own, look outward for one:
                // 1) descendants — e.g. a clickable LinearLayout wrapping an icon + a text label
                //    (custom bottom-nav / tab items) → "Home".
                // 2) siblings, only for toggles — a Switch/CheckBox lives next to its title in a
                //    row (icon | title + desc | switch), so its label is a sibling, not a child
                //    → "Lock Card Temporarily" instead of "MaterialSwitch".
                findLabelInChildren(view)
                    ?: if (isToggle(view)) findLabelInSiblings(view) else null
            } else {
                null
            }

        return LabelResolver.resolve(
            contentDescription = contentDescription ?: resolvedLabel,
            text = text,
            className = view.javaClass.simpleName,
            fallback = view.id.toString(),
        )
    }

    /**
     * Breadth-first search over [root]'s descendants for a human-readable label, preferring a
     * non-blank content description, then non-blank [android.widget.TextView] text.
     */
    private fun findLabelInChildren(root: View): String? {
        if (root !is ViewGroup) return null
        return firstLabelIn(childrenOf(root))
    }

    /**
     * Searches the siblings of [view] (its parent's other descendants) for a label. Used for
     * toggles, whose descriptive text sits beside the control rather than inside it.
     */
    private fun findLabelInSiblings(view: View): String? {
        val parent = view.parent as? ViewGroup ?: return null
        val queue = LinkedList<View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child !== view) {
                queue.add(child)
            }
        }
        return firstLabelIn(queue)
    }

    private fun childrenOf(group: ViewGroup): LinkedList<View> {
        val queue = LinkedList<View>()
        for (i in 0 until group.childCount) {
            queue.add(group.getChildAt(i))
        }
        return queue
    }

    /**
     * Drains [queue] in BFS order, returning the first non-blank content description or
     * [android.widget.TextView] text found, enqueuing each [ViewGroup]'s children as it goes.
     */
    private fun firstLabelIn(queue: LinkedList<View>): String? {
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_LABEL_SEARCH_NODES) {
            visited++
            val child = queue.removeFirst()

            val description = child.contentDescription?.toString()
            if (!description.isNullOrBlank()) {
                return description
            }

            val text = (child as? android.widget.TextView)?.text?.toString()
            if (!text.isNullOrBlank()) {
                return text
            }

            if (child is ViewGroup) {
                queue.addAll(childrenOf(child))
            }
        }
        return null
    }

    private fun isJetpackComposeView(view: View): Boolean =
        view::class.java.name.startsWith("androidx.compose.ui.platform.ComposeView")

    private val View.isVisible: Boolean
        get() = visibility == View.VISIBLE

    private companion object {
        /** Upper bound on nodes scanned when resolving a label, to keep traversal cheap. */
        const val MAX_LABEL_SEARCH_NODES = 100

        /** Safe placeholder used when a password field has no usable non-value label. */
        const val PASSWORD_FIELD_LABEL = "password field"

        /** Qualified name of Material's slider base class — see [isMaterialSlider]. */
        const val CLASS_NAME_MATERIAL_BASE_SLIDER = "com.google.android.material.slider.BaseSlider"
    }
}
