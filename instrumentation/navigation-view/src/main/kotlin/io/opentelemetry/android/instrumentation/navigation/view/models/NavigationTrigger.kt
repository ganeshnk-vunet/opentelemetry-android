/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view.models

/**
 * Describes the most likely cause of a navigation transition when the signal is available.
 *
 * @property value Stable string written to the `navigation.trigger` span attribute.
 */
internal enum class NavigationTrigger(
    val value: String,
) {
    /** The collector observed a system back press immediately before the transition. */
    BACK_PRESS("back_press"),

    /** The host supports back press observation and no matching back press was observed. */
    PROGRAMMATIC("programmatic"),

    /** The collector does not have enough signal to attribute the transition confidently. */
    UNKNOWN("unknown"),
}