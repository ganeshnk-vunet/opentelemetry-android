/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.internal.services

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * Default [DeviceCapacityReader], backed by the real Android system APIs. Logic ported verbatim
 * from `instrumentation/system-metrics`'s `DefaultDeviceMetricsReader`, which reads the same two
 * facts as part of a larger per-sample batch; this reader exists so `core` can read them once at
 * resource-build time without depending on that opt-in instrumentation module.
 */
object DefaultDeviceCapacityReader : DeviceCapacityReader {
    override fun readTotalRamBytes(context: Context): Long =
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.totalMem
        } catch (_: Exception) {
            -1L
        }

    override fun readTotalDiskBytes(): Long =
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            if (blockSize <= 0) -1L else stat.blockCountLong * blockSize
        } catch (_: Exception) {
            -1L
        }
}
