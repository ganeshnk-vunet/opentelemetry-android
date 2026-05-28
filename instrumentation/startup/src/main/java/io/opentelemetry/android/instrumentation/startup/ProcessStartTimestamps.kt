/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.startup

internal object ProcessStartTimestamps {
    @Volatile var attachBaseContextEpochMs: Long = 0L
    @Volatile var contentProviderEpochMs: Long = 0L
}
