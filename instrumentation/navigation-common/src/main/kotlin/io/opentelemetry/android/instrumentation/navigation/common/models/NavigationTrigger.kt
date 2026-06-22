/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common.models

/**
 * What caused a navigation transition, written to the `navigation.trigger` span attribute.
 *
 * Shared by every navigation collector (Nav2, Nav3, View) so the attribute contract is defined in
 * exactly one place. Only a [NavigationTransitionType.POP] that follows a recent back press is
 * reported as [BACK_PRESS]; other pops are [PROGRAMMATIC] and forward transitions are [UNKNOWN].
 *
 * @property value Stable string written to the `navigation.trigger` span attribute.
 */
enum class NavigationTrigger(
    val value: String,
) {
    /** A [NavigationTransitionType.POP] attributed to a recent back press. */
    BACK_PRESS("back_press"),

    /** A pop not preceded by a back press, i.e. a code-driven `popBackStack`/`navigateUp`. */
    PROGRAMMATIC("programmatic"),

    /** A forward transition ([NavigationTransitionType.PUSH]/[NavigationTransitionType.REPLACE]). */
    UNKNOWN("unknown"),
    ;

    companion object {
        /**
         * How long a recorded back press stays eligible to be attributed to the next pop. A pop
         * that arrives later than this is treated as [PROGRAMMATIC], guarding against a stale signal
         * (e.g. a back press that did not actually move the back stack) being misattributed.
         */
        const val BACK_PRESS_SIGNAL_TTL_NANOS: Long = 1_000_000_000L
    }
}
