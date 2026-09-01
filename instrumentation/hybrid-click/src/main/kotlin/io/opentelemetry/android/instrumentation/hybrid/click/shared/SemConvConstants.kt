/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

internal const val ATTR_WIDGET_SOURCE = "app.widget.source"

/** Which gesture produced the interaction — see [InteractionType] for the values. */
internal const val ATTR_INTERACTION_TYPE = "interaction.type"

/** Boolean state of a tapped toggle (switch / checkbox / radio), when the target is checkable. */
internal const val ATTR_WIDGET_CHECKED = "ui.control.value.checked"

/** Kind of widget tapped — see the `WIDGET_TYPE_*` values. */
internal const val ATTR_WIDGET_TYPE = "app.widget.type"

/**
 * Canonical successor to [ATTR_WIDGET_TYPE]. Carries the exact same normalized value; canonical
 * treats `app.widget.type` as platform wire only and prefers this name. Both are emitted so
 * existing `app.widget.type` queries keep working.
 */
internal const val ATTR_CONTROL_TYPE = "ui.control.type"

/**
 * Whether the tapped control belongs to a single-choice or multi-choice group — see
 * [SELECTION_MODE_SINGLE] / [SELECTION_MODE_MULTIPLE]. Emitted only for widget kinds whose
 * selection semantics are unambiguous from the type alone (radio, tab, dropdown, switch,
 * checkbox, toggle); omitted for plain buttons, text, images, and other non-selection controls,
 * where the concept doesn't apply.
 */
internal const val ATTR_CONTROL_SELECTION_MODE = "ui.control.selection_mode"

/** One choice active at a time within a group — e.g. radio buttons, tabs, a dropdown's options. */
internal const val SELECTION_MODE_SINGLE = "single"

/** Each control's state is independent of its siblings — e.g. switches, checkboxes. */
internal const val SELECTION_MODE_MULTIPLE = "multiple"

internal const val SOURCE_COMPOSE = "compose"
internal const val SOURCE_VIEW = "view"

// Normalized widget kinds, shared by the View and Compose paths so both report the same vocabulary.
internal const val WIDGET_TYPE_BUTTON = "button"
internal const val WIDGET_TYPE_SWITCH = "switch"
internal const val WIDGET_TYPE_CHECKBOX = "checkbox"
internal const val WIDGET_TYPE_RADIO = "radio"
internal const val WIDGET_TYPE_TOGGLE = "toggle"
internal const val WIDGET_TYPE_TEXT_FIELD = "text_field"
internal const val WIDGET_TYPE_IMAGE = "image"
internal const val WIDGET_TYPE_TAB = "tab"
internal const val WIDGET_TYPE_DROPDOWN = "dropdown"
internal const val WIDGET_TYPE_TEXT = "text"
internal const val WIDGET_TYPE_VIEW = "view"
internal const val WIDGET_TYPE_UNKNOWN = "unknown"

/**
 * Resolves [ATTR_CONTROL_SELECTION_MODE] from a normalized [widgetType], or `null` when
 * selection doesn't apply to that kind (buttons, text, images, unknown, …).
 *
 * The mapping follows ordinary Android/Compose semantics for each kind, not per-instance
 * behaviour: a [WIDGET_TYPE_RADIO] is single-select because that's what a radio button *means*,
 * without inspecting whether it actually sits in a `RadioGroup`. [WIDGET_TYPE_TAB] and
 * [WIDGET_TYPE_DROPDOWN] are included on the same reasoning (`Role.Tab` / `Role.DropdownList` are
 * inherently single-choice); [WIDGET_TYPE_SWITCH], [WIDGET_TYPE_CHECKBOX] and [WIDGET_TYPE_TOGGLE]
 * are multi-select because each one's state is independent of its siblings.
 */
internal fun resolveSelectionMode(widgetType: String): String? =
    when (widgetType) {
        WIDGET_TYPE_RADIO, WIDGET_TYPE_TAB, WIDGET_TYPE_DROPDOWN -> SELECTION_MODE_SINGLE
        WIDGET_TYPE_SWITCH, WIDGET_TYPE_CHECKBOX, WIDGET_TYPE_TOGGLE -> SELECTION_MODE_MULTIPLE
        else -> null
    }
