/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

/**
 * Normalized tap target metadata used to build hybrid click spans.
 *
 * [source] identifies where the target came from (`view` or `compose`).
 *
 * [checkedStateProvider] is supplied only when the target is a toggle (switch / checkbox / radio).
 * It is a deferred read of the live checked state rather than a captured value: the click span is
 * emitted before the touch is delegated to the underlying widget, so reading the state lazily (on
 * the next main-loop tick) reports the *resulting* state after the toggle flips.
 */
internal data class TapTarget(
    val source: String,
    val widgetId: String,
    val widgetName: String,
    val label: String,
    val x: Long,
    val y: Long,
    val checkedStateProvider: (() -> Boolean?)? = null,
)
