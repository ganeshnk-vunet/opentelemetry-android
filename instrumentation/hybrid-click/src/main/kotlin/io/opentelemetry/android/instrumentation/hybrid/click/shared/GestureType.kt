/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

/**
 * Which raw pointer gesture produced an interaction, written to the `ui.gesture.type` span
 * attribute.
 *
 * Resolved in exactly one place ([TapGestureClassifier]) so the attribute contract is defined once.
 * A pointer that leaves the touch slop is a [DRAG]; one that stays within it is a [TAP] below
 * [TapGestureClassifier.longPressTimeoutMs] and a [LONG_PRESS] at or above it.
 *
 * This is deliberately distinct from `interaction.type`, which names the *semantic* interaction
 * (e.g. a toggle or a slider) and is derived from the control that was hit. The same gesture can
 * produce different interactions depending on the target, so both are emitted: `ui.gesture.type`
 * always answers "what did the finger do", independently of what the control was.
 *
 * @property value Stable string written to the `ui.gesture.type` span attribute.
 */
internal enum class GestureType(
    val value: String,
) {
    /** A qualifying press released before the long-press threshold. */
    TAP("tap"),

    /** A qualifying press held to at least the long-press threshold before release. */
    LONG_PRESS("long_press"),

    /**
     * A pointer that left the touch slop before release, i.e. a drag, scroll or fling.
     *
     * Reported so the caller can decide what the movement meant for the control underneath —
     * dragging a slider is a real interaction, whereas scrolling a list is not. Emitting a span for
     * one is [ClickEventGenerator]'s decision, not the classifier's; before this kind existed such a
     * gesture was simply discarded here, which is why a slider drag produced no telemetry at all.
     */
    DRAG("drag"),
}
