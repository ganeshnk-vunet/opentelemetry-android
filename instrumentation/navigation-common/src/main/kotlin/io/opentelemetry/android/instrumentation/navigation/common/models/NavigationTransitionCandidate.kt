/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common.models

/**
 * All inputs required to emit one `ui.navigation` span for a detected screen transition.
 *
 * @property source Previously visible screen, if any.
 * @property destination Screen that became visible.
 * @property transitionType Inferred direction of the transition ([NavigationTransitionType]).
 * @property entryType How the destination was entered.
 * @property timestampNanos Wall-clock time from [io.opentelemetry.sdk.common.Clock.now] (nanoseconds since epoch).
 * @property stackDepthBefore Depth of the navigator's tracked stack before the transition, or `null`
 *   where the framework exposes no depth. What "depth" counts is framework-specific: Nav3 reports
 *   true back-stack sizes; Nav2 reports its own shadow stack, which *retains* the destination on a
 *   pop (3 → 2, not 3 → 1); the View collector reports per-`FragmentManager` back-stack counts for
 *   Fragment transitions and `null` for Activity transitions, which have no depth concept.
 * @property stackDepthAfter Depth of the same tracked stack after the transition, or `null`.
 */
data class NavigationTransitionCandidate(
    val source: NavigationNode?,
    val destination: NavigationNode,
    val transitionType: NavigationTransitionType,
    val entryType: NavigationEntryType,
    val timestampNanos: Long,
    val stackDepthBefore: Int? = null,
    val stackDepthAfter: Int? = null,
)
