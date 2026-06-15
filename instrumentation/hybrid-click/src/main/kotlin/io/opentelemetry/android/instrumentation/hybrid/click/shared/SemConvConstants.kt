/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

internal const val UI_CLICK_SPAN_NAME = "ui.click"
internal const val ATTR_WIDGET_SOURCE = "app.widget.source"

/** Boolean state of a tapped toggle (switch / checkbox / radio), when the target is checkable. */
internal const val ATTR_WIDGET_CHECKED = "app.widget.checked"
internal const val SOURCE_COMPOSE = "compose"
internal const val SOURCE_VIEW = "view"
