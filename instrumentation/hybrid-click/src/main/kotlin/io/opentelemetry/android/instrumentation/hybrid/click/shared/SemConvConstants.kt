/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

internal const val UI_CLICK_SPAN_NAME = "ui.click"
internal const val ATTR_WIDGET_SOURCE = "app.widget.source"

/** Boolean state of a tapped toggle (switch / checkbox / radio), when the target is checkable. */
internal const val ATTR_WIDGET_CHECKED = "app.widget.checked"

/** Kind of widget tapped — see the `WIDGET_TYPE_*` values. */
internal const val ATTR_WIDGET_TYPE = "app.widget.type"

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
