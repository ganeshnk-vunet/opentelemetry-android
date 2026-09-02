/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common.models

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Direct tests for the rule the three collectors share.
 *
 * The collector tests cover the happy paths through their own navigators, but this logic was lifted
 * out of all three into one published object, so a single regression in the `when` below would reach
 * every navigator at once. These pin the edges those tests do not reach: the exact TTL boundary, a
 * missing back-press timestamp, and a clock that appears to move backwards.
 */
class NavigationTriggerResolverTest {
    private val ttl = NavigationTriggerResolver.BACK_PRESS_SIGNAL_TTL_NANOS

    @Test
    fun `pop within the ttl is attributed to the back press`() {
        val trigger = NavigationTriggerResolver.resolve(NavigationTransitionType.POP, 1_000L, 1_000L + ttl - 1)

        assertThat(trigger).isEqualTo(NavigationTrigger.BACK_PRESS)
    }

    /** The boundary is inclusive: exactly at the TTL still counts as a back press. */
    @Test
    fun `pop exactly at the ttl is attributed to the back press`() {
        val trigger = NavigationTriggerResolver.resolve(NavigationTransitionType.POP, 1_000L, 1_000L + ttl)

        assertThat(trigger).isEqualTo(NavigationTrigger.BACK_PRESS)
    }

    @Test
    fun `pop one nanosecond past the ttl is programmatic`() {
        val trigger = NavigationTriggerResolver.resolve(NavigationTransitionType.POP, 1_000L, 1_000L + ttl + 1)

        assertThat(trigger).isEqualTo(NavigationTrigger.PROGRAMMATIC)
    }

    @Test
    fun `pop with no recorded back press is programmatic`() {
        val trigger = NavigationTriggerResolver.resolve(NavigationTransitionType.POP, null, 1_000L)

        assertThat(trigger).isEqualTo(NavigationTrigger.PROGRAMMATIC)
    }

    /**
     * A back press stamped later than "now" yields a negative elapsed time, which is `<= ttl` and so
     * still reads as recent. Documented rather than guarded: both timestamps come from the same
     * injected [io.opentelemetry.sdk.common.Clock], so the only way to observe this is a clock that
     * moved backwards between the two reads.
     */
    @Test
    fun `back press stamped in the future still reads as recent`() {
        val trigger = NavigationTriggerResolver.resolve(NavigationTransitionType.POP, 5_000L, 1_000L)

        assertThat(trigger).isEqualTo(NavigationTrigger.BACK_PRESS)
    }

    @Test
    fun `forward transitions are unknown regardless of a pending back press`() {
        assertThat(NavigationTriggerResolver.resolve(NavigationTransitionType.PUSH, 1_000L, 1_000L))
            .isEqualTo(NavigationTrigger.UNKNOWN)
        assertThat(NavigationTriggerResolver.resolve(NavigationTransitionType.REPLACE, 1_000L, 1_000L))
            .isEqualTo(NavigationTrigger.UNKNOWN)
        assertThat(NavigationTriggerResolver.resolve(NavigationTransitionType.PUSH, null, 1_000L))
            .isEqualTo(NavigationTrigger.UNKNOWN)
        assertThat(NavigationTriggerResolver.resolve(NavigationTransitionType.REPLACE, null, 1_000L))
            .isEqualTo(NavigationTrigger.UNKNOWN)
    }
}
