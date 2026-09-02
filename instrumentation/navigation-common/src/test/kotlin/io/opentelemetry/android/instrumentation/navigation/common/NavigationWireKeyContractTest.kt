/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys** of the `ui.navigation` signal.
 *
 * Every other navigation test asserts through `NavigationConstants.*` and
 * [NavigationTrigger], so they all keep passing if a constant's string value is reverted or
 * mistyped — but the emitted attribute names are the actual contract with dashboards and alerts,
 * and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 */
class NavigationWireKeyContractTest {
    @Test
    fun `navigation attribute keys use the canonical wire names`() {
        assertThat(NavigationConstants.SPAN_NAME).isEqualTo("ui.navigation")
        assertThat(NavigationConstants.NAVIGATION_SOURCE_TYPE_KEY.key).isEqualTo("navigation.source.type")
        assertThat(NavigationConstants.NAVIGATION_SOURCE_NAME_KEY.key).isEqualTo("navigation.source.name")
        assertThat(NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY.key).isEqualTo("navigation.destination.type")
        assertThat(NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY.key).isEqualTo("navigation.destination.name")
        assertThat(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY.key).isEqualTo("navigation.transition.type")
        assertThat(NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY.key).isEqualTo("navigation.entry.type")
        assertThat(NavigationConstants.NAVIGATION_TRIGGER_KEY.key).isEqualTo("navigation.trigger")
        assertThat(NavigationConstants.NAVIGATION_TIMESTAMP_NS_KEY.key).isEqualTo("navigation.timestamp_ns")
        assertThat(NavigationConstants.NAVIGATION_IS_INITIAL_KEY.key).isEqualTo("navigation.is_initial")
        assertThat(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY.key).isEqualTo("navigation.stack_depth.before")
        assertThat(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY.key).isEqualTo("navigation.stack_depth.after")
    }

    @Test
    fun `navigation trigger vocabulary uses the canonical values`() {
        assertThat(NavigationTrigger.BACK_PRESS.value).isEqualTo("back_press")
        assertThat(NavigationTrigger.PROGRAMMATIC.value).isEqualTo("programmatic")
        assertThat(NavigationTrigger.UNKNOWN.value).isEqualTo("unknown")
        assertThat(NavigationTrigger.USER_TAP.value).isEqualTo("user_tap")
    }

    /**
     * `navigation.action` was the pre-canonical name for the transition type. It survives only in
     * unreferenced code in navigation-view and must never become an emitted key again.
     */
    @Test
    fun `superseded transition-type wire key is not used`() {
        assertThat(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY.key).isNotEqualTo("navigation.action")
    }
}
