/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common.models

/**
 * Attributes a transition to a [NavigationTrigger], shared by every navigation collector (Nav2,
 * Nav3, View) so the rule is defined in exactly one place.
 *
 * Deliberately pure: the caller owns the back-press timestamp and passes it in, because each
 * collector reaches its clock differently (View takes a `Clock` constructor parameter, the Compose
 * collectors read `OpenTelemetryRum.clock`).
 *
 * Callers must clear their stored back-press timestamp after calling [resolve] — every branch of
 * the rule consumes the signal, so the clear is unconditional.
 *
 * Public only because the three collectors live in sibling Gradle modules, the same reason
 * [NavigationTrigger] and `NavigationConstants` are public; the TTL below stays internal.
 */
object NavigationTriggerResolver {
    /**
     * How long a recorded back press stays eligible to be attributed to the next pop. A pop that
     * arrives later is treated as programmatic, guarding against a stale back-press signal being
     * misattributed. Implementation detail, intentionally not part of the published API.
     */
    internal const val BACK_PRESS_SIGNAL_TTL_NANOS: Long = 1_000_000_000L

    /**
     * Returns [NavigationTrigger.BACK_PRESS] only for a [NavigationTransitionType.POP] preceded by a
     * back press recorded within [BACK_PRESS_SIGNAL_TTL_NANOS] of [nowNanos]; other pops are
     * [NavigationTrigger.PROGRAMMATIC] and forward transitions are [NavigationTrigger.UNKNOWN].
     */
    fun resolve(
        transitionType: NavigationTransitionType,
        lastBackPressAtNanos: Long?,
        nowNanos: Long,
    ): NavigationTrigger =
        when (transitionType) {
            NavigationTransitionType.POP -> {
                val isRecentBackPress =
                    lastBackPressAtNanos != null &&
                        nowNanos - lastBackPressAtNanos <= BACK_PRESS_SIGNAL_TTL_NANOS
                if (isRecentBackPress) {
                    NavigationTrigger.BACK_PRESS
                } else {
                    NavigationTrigger.PROGRAMMATIC
                }
            }

            NavigationTransitionType.PUSH,
            NavigationTransitionType.REPLACE,
            -> NavigationTrigger.UNKNOWN
        }
}
