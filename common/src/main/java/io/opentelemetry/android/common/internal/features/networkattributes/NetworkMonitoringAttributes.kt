/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.features.networkattributes

import io.opentelemetry.api.common.AttributeKey

/**
 * Shared attribute keys for network monitoring telemetry.
 *
 * This type is in an `internal`-named package and is **not** part of the stable public API.
 */
object NetworkMonitoringAttributes {
    @JvmField
    val NETWORK_CONNECTION_METERED: AttributeKey<Boolean> =
        AttributeKey.booleanKey("network.connection.metered")
}
