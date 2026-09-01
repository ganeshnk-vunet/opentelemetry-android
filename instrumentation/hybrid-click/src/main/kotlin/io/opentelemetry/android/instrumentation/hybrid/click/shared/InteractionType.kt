/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

/**
 * Which gesture produced an interaction, written to the `interaction.type` span attribute.
 *
 * Resolved in exactly one place ([TapGestureClassifier]) so the attribute contract is defined once.
 * The two kinds are separated purely by how long the pointer was down: a qualifying gesture that
 * stays within the touch slop is a [TAP] below [TapGestureClassifier.longPressTimeoutMs] and a
 * [LONG_PRESS] at or above it.
 *
 * @property value Stable string written to the `interaction.type` span attribute.
 */
internal enum class InteractionType(
    val value: String,
) {
    /** A qualifying press released before the long-press threshold. */
    TAP("tap"),

    /** A qualifying press held to at least the long-press threshold before release. */
    LONG_PRESS("long_press"),
}
