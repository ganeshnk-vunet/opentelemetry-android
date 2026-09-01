/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.internal.services

import android.content.Context

/**
 * One-shot reads of device capacity facts — total RAM and total disk space — that are static for
 * the life of the process, unlike the dynamic `available`/`free` readings
 * `instrumentation/system-metrics` polls repeatedly.
 *
 * An interface (rather than a plain object) purely so callers can inject a fake in tests, the same
 * shape `instrumentation/system-metrics`'s own `DeviceMetricsReader` already uses. This has no
 * lifecycle and nothing to listen for, so unlike [Service]/[ServicesFactory] it is not registered
 * as a process-lifetime singleton — [DefaultDeviceCapacityReader] is called directly.
 *
 * Public rather than `internal` because Kotlin `internal` is scoped per Gradle module — `:core`,
 * the only current caller, is a separate module from `:services` and would not see an `internal`
 * declaration here. Not intended as a stable public API; it lives under the `internal.services`
 * package like everything else in this module, and is tracked by this module's binary-compatibility
 * check for that reason.
 */
interface DeviceCapacityReader {
    /** Total device RAM in bytes, or `-1` if it could not be read. */
    fun readTotalRamBytes(context: Context): Long

    /** Total disk space of the internal data partition in bytes, or `-1` if it could not be read. */
    fun readTotalDiskBytes(): Long
}
