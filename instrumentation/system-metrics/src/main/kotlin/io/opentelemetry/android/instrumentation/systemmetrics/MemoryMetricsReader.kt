/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import android.os.Debug

/**
 * Reads memory-related metrics for the current process.
 *
 * - Heap used: Java heap bytes currently in use.
 * - Native heap: Native allocations tracked by [Debug].
 * - Footprint: Proportional Set Size read from [Debug.MemoryInfo] (shared memory counted
 *   proportionally), converted from kB to bytes.
 */
internal class MemoryMetricsReader {
    /** Java heap bytes currently used (total - free). */
    fun readHeapUsedBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Java heap bytes committed (allocated) from the OS — includes used + free portions. */
    fun readHeapAllocatedBytes(): Long = Runtime.getRuntime().totalMemory()

    /** Java heap bytes currently free (committed but not in use). */
    fun readHeapFreeBytes(): Long = Runtime.getRuntime().freeMemory()

    /** Native heap bytes allocated via malloc/JNI. */
    fun readNativeHeapUsedBytes(): Long = Debug.getNativeHeapAllocatedSize()

    /**
     * Memory footprint in **bytes**, from Proportional Set Size.
     *
     * [Debug.MemoryInfo.totalPss] is denominated in kB, so it is scaled via [pssKbToBytes] rather
     * than left as-is: canonical defines `process.memory.footprint` in bytes, the same field iOS
     * feeds from `phys_footprint` (bytes), so shipping kB under that shared name would compare
     * kB against bytes on any cross-platform chart.
     *
     * [Debug.getMemoryInfo] is a blocking binder call — only run this at a longer
     * interval (≥ 30 s) to avoid overhead.
     */
    fun readFootprintBytes(): Long {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return pssKbToBytes(mi.totalPss.toLong())
    }

    companion object {
        private const val BYTES_PER_KB = 1024L

        /**
         * Pure kB→bytes conversion, pulled out of [readFootprintBytes] so the actual arithmetic is
         * directly unit-testable without Robolectric — [Debug.MemoryInfo] can't be exercised with
         * a real, known input in a JVM test, only stubbed to 0, which would prove nothing about the
         * multiplication itself.
         *
         * Widened to `Long` before scaling — `totalPss` is an `Int` of kB, so scaling in `Int`
         * would overflow above ~2 GB of PSS.
         */
        internal fun pssKbToBytes(pssKb: Long): Long = pssKb * BYTES_PER_KB
    }
}
