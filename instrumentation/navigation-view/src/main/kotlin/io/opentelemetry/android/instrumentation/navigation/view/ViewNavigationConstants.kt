/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import io.opentelemetry.api.common.AttributeKey

internal object ViewNavigationConstants {
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
}
