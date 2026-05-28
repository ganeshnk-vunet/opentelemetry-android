/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.startup

import com.google.auto.service.AutoService
import io.opentelemetry.android.common.StartupTimestampProvider

@AutoService(StartupTimestampProvider::class)
internal class StartupTimestampProviderImpl : StartupTimestampProvider {
    override val attachBaseContextEpochMs: Long
        get() = ProcessStartTimestamps.attachBaseContextEpochMs

    override val contentProviderEpochMs: Long
        get() = ProcessStartTimestamps.contentProviderEpochMs
}
