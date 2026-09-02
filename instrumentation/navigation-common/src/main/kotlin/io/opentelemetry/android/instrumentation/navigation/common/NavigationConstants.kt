/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.api.common.AttributeKey

object NavigationConstants {
    const val SPAN_NAME: String = "ui.navigation"

    @JvmField
    val NAVIGATION_SOURCE_TYPE_KEY: AttributeKey<String> = AttributeKey.stringKey("navigation.source.type")

    @JvmField
    val NAVIGATION_SOURCE_NAME_KEY: AttributeKey<String> = AttributeKey.stringKey("navigation.source.name")

    @JvmField
    val NAVIGATION_DESTINATION_TYPE_KEY: AttributeKey<String> =
        AttributeKey.stringKey("navigation.destination.type")

    @JvmField
    val NAVIGATION_DESTINATION_NAME_KEY: AttributeKey<String> =
        AttributeKey.stringKey("navigation.destination.name")

    @JvmField
    val NAVIGATION_TRANSITION_TYPE_KEY: AttributeKey<String> =
        AttributeKey.stringKey("navigation.transition.type")

    @JvmField
    val NAVIGATION_ENTRY_TYPE_KEY: AttributeKey<String> = AttributeKey.stringKey("navigation.entry.type")

    @JvmField
    val NAVIGATION_TRIGGER_KEY: AttributeKey<String> = AttributeKey.stringKey("navigation.trigger")

    @JvmField
    val NAVIGATION_TIMESTAMP_NS_KEY: AttributeKey<Long> = AttributeKey.longKey("navigation.timestamp_ns")

    /** True on the first navigation of the process. See `NavigationColdStartTracker`. */
    @JvmField
    val NAVIGATION_IS_INITIAL_KEY: AttributeKey<Boolean> = AttributeKey.booleanKey("navigation.is_initial")

    /**
     * Depth of the navigator's tracked stack before the transition. Absent where the framework has
     * no depth to report — see `NavigationTransitionCandidate.stackDepthBefore`.
     */
    @JvmField
    val NAVIGATION_STACK_DEPTH_BEFORE_KEY: AttributeKey<Long> =
        AttributeKey.longKey("navigation.stack_depth.before")

    /** Depth of the navigator's tracked stack after the transition. */
    @JvmField
    val NAVIGATION_STACK_DEPTH_AFTER_KEY: AttributeKey<Long> =
        AttributeKey.longKey("navigation.stack_depth.after")
}
