/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.view

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import io.opentelemetry.android.instrumentation.hybrid.click.shared.LabelResolver
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SOURCE_VIEW
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTarget
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTargetDetector
import java.util.LinkedList

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
            checkedStateProvider = checkedStateProviderOf(clickTarget),
        )
    }

    /**
     * Returns a live checked-state reader, but only for genuine toggle widgets. We intentionally do
     * not key off the [android.widget.Checkable] interface: `MaterialButton` implements it while
     * being an ordinary (non-toggle) button, which would otherwise tag every Material button — e.g.
     * a dialog's "OK" — with `checked=false`.
     */
    private fun checkedStateProviderOf(view: View): (() -> Boolean?)? =
        when (view) {
            is CompoundButton -> ({ view.isChecked })
            is CheckedTextView -> ({ view.isChecked })
            else -> null
        }

    private fun isToggle(view: View): Boolean = view is CompoundButton || view is CheckedTextView

    private fun isValidClickTarget(view: View): Boolean =
        view.isVisible && (view.isClickable || view is EditText)

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
    }
}
