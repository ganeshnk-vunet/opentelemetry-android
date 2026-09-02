/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs

/**
 * One-shot reads of device capacity facts — total RAM and total disk space — that are static for
 * the life of the process, unlike the dynamic `available`/`free` readings
 * `instrumentation/system-metrics` polls repeatedly.
 *
 * An interface rather than a plain object purely so [AndroidResource] can inject a fake in tests.
 * It lives in `:core` (the only caller) and is `internal`, so it stays off the published API — a
 * test seam should not become a supported type.
 */
internal interface DeviceCapacityReader {
    /** Total device RAM in bytes, or a negative value if it could not be read. */
    fun readTotalRamBytes(context: Context): Long

    /** Total disk space of the internal data partition in bytes, or negative if unreadable. */
    fun readTotalDiskBytes(): Long
}

/**
 * Default [DeviceCapacityReader], backed by the real Android system APIs.
 *
 * Both values are memoized after the first successful read. This is not just an optimisation:
 * [AndroidResource.createDefault] runs more than once per SDK init — `OpenTelemetryRumBuilder`
 * builds a default resource in a field initializer and `OpenTelemetryRumInitializer` builds another
 * — and both typically run on the main thread during `Application.onCreate`. `StatFs` is a
 * filesystem `statvfs` call that can trip `StrictMode.detectDiskReads`, so it is worth paying at
 * most once. Memoizing here rather than in [AndroidResource] keeps injected test fakes
 * un-memoized, so tests stay deterministic.
 *
 * Failed reads are deliberately *not* cached, so a transient failure can recover on the next
 * resource build rather than being pinned for the life of the process.
 */
internal object DefaultDeviceCapacityReader : DeviceCapacityReader {
    private const val UNAVAILABLE = -1L

    // Benign race: two initializing threads may both compute a value, but the reads are pure and
    // idempotent, so the loser simply overwrites with an identical result. Cheaper than locking on
    // a startup path.
    @Volatile
    private var cachedTotalRamBytes: Long = UNAVAILABLE

    @Volatile
    private var cachedTotalDiskBytes: Long = UNAVAILABLE

    override fun readTotalRamBytes(context: Context): Long {
        val cached = cachedTotalRamBytes
        if (cached >= 0) {
            return cached
        }
        val read = readTotalRam(context)
        if (read >= 0) {
            cachedTotalRamBytes = read
        }
        return read
    }

    override fun readTotalDiskBytes(): Long {
        val cached = cachedTotalDiskBytes
        if (cached >= 0) {
            return cached
        }
        val read = readTotalDisk()
        if (read >= 0) {
            cachedTotalDiskBytes = read
        }
        return read
    }

    private fun readTotalRam(context: Context): Long =
        try {
            // applicationContext: this value outlives any Activity, and the reader is a
            // process-lifetime singleton, so holding the narrower context would be a leak risk.
            val activityManager =
                context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.totalMem
        } catch (_: Exception) {
            UNAVAILABLE
        }

    private fun readTotalDisk(): Long =
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            if (blockSize <= 0) UNAVAILABLE else stat.blockCountLong * blockSize
        } catch (_: Exception) {
            UNAVAILABLE
        }

    /** Clears the memoized values so each test observes a fresh read. */
    internal fun resetForTesting() {
        cachedTotalRamBytes = UNAVAILABLE
        cachedTotalDiskBytes = UNAVAILABLE
    }
}
