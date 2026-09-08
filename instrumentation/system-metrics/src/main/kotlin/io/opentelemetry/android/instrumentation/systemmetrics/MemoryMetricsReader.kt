/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import android.os.Debug
import java.io.File

/**
 * Reads memory-related metrics for the current process.
 *
 * - Heap used: Java heap bytes currently in use.
 * - Native heap: Native allocations tracked by [Debug].
 * - Footprint: Proportional Set Size read from [Debug.MemoryInfo] (shared memory counted
 *   proportionally), converted from kB to bytes.
 * - Resident set size: pages currently mapped into physical RAM, read from `/proc/self/status`.
 *
 * Native heap, footprint and RSS are three different statistics, not three names for one — see
 * [readResidentSetSizeBytes].
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
     * Resident set size in **bytes** — pages currently mapped into physical RAM.
     *
     * This is a genuinely different statistic from [readNativeHeapUsedBytes], which reports native
     * heap allocated via malloc/JNI. Both are emitted; neither is a rename of the other. RSS is what
     * canonical `process.memory.resident` means, so this is the field a cross-platform memory
     * chart can actually compare.
     *
     * Read from `/proc/self/status`'s `VmRSS` line rather than `/proc/self/statm`, deliberately:
     * `statm` reports resident **pages**, which have to be multiplied by the page size, and Android
     * 15 supports 16 kB pages — so the usual hardcoded 4096 is wrong on those devices, and reading
     * the real page size adds an API dependency for no benefit. `VmRSS` is denominated in kB
     * directly, so there is no page-size assumption to get wrong.
     *
     * Returns [UNAVAILABLE] when the file cannot be read or carries no `VmRSS` line, so the caller
     * can omit the attribute rather than publish a fabricated `0` — a live process never has zero
     * resident pages, so a zero here would be indistinguishable from a real reading and quietly
     * wrong on a chart.
     *
     * Cheap enough for the per-sample path: a small procfs read, not a binder round trip like
     * [readFootprintBytes].
     *
     * Catches [Throwable] deliberately, not just [IOException]. This runs inside the sampling task
     * that `SystemMetricsSpanEmitter` hands to `ScheduledExecutorService.scheduleAtFixedRate`, and
     * that contract suppresses all later executions once a task throws — so anything escaping here
     * would silently stop the entire `app.metrics` signal, not merely drop this one attribute.
     * A failed reading already has a defined outcome ([UNAVAILABLE], attribute omitted); that
     * outcome should hold for every failure, whatever its type.
     */
    fun readResidentSetSizeBytes(statusFile: File = File(PROC_SELF_STATUS)): Long =
        try {
            parseVmRssKb(statusFile.readText())?.let { it * BYTES_PER_KB } ?: UNAVAILABLE
        } catch (_: Throwable) {
            UNAVAILABLE
        }

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
        private const val PROC_SELF_STATUS = "/proc/self/status"

        /** Sentinel for "no reading available"; callers omit the attribute rather than emit it. */
        const val UNAVAILABLE: Long = -1L

        /**
         * Pulls the `VmRSS` value (in kB) out of `/proc/self/status` content, or `null` when the
         * line is absent or unparseable. Pure, so the actual parsing is unit-testable without a
         * device — same reasoning as [pssKbToBytes].
         *
         * Matched with an exact `VmRSS:` prefix rather than a `contains`: the same file carries
         * `RssAnon:`, `RssFile:` and `RssShmem:` lines, and on some kernels `VmRSS` is followed by
         * those as a breakdown of the very same total. Matching loosely would pick whichever
         * appeared first and silently report a component as the whole.
         */
        internal fun parseVmRssKb(statusContent: String): Long? {
            val line =
                statusContent
                    .lineSequence()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?: return null
            return line
                .removePrefix("VmRSS:")
                .trim()
                .substringBefore(' ')
                .toLongOrNull()
        }

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
