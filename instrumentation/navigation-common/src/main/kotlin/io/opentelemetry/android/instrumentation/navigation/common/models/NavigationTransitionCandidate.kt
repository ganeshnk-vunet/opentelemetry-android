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
 * @property intentAtNanos When the back press a collector is holding was recorded, on the same
 *   clock as [timestampNanos], or `null` when it holds none. Set whatever the trigger resolver
 *   decided: its 1 s TTL governs whether the press may *name* `navigation.trigger`, not whether the
 *   navigation may be *timed*, or a back navigation slow enough to be worth investigating would
 *   report no duration at all.
 *
 *   A pending press is not proof that *this* transition is the one it caused — it may have
 *   dismissed a dialog, or the user may have tapped forward instead — so `NavigationSpanEmitter`
 *   times from it **only on a [NavigationTransitionType.POP]**, and otherwise falls back to the
 *   live interaction context. A tap-driven navigation carries `null` here and is always timed from
 *   that context.
 */
data class NavigationTransitionCandidate(
    val source: NavigationNode?,
    val destination: NavigationNode,
    val transitionType: NavigationTransitionType,
    val entryType: NavigationEntryType,
    val timestampNanos: Long,
    val stackDepthBefore: Int? = null,
    val stackDepthAfter: Int? = null,
    val intentAtNanos: Long? = null,
)
