/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire key** of the `app.metrics` attribute renamed to its canonical name, and the
 * **unit** of the value now emitted under it.
 *
 * `SystemMetricsSpanEmitterTest` asserts through `ATTR_NATIVE_USED` / `ATTR_FOOTPRINT`, so it stays
 * green if a constant's string value is reverted or mistyped — the emitted attribute names are the
 * actual contract with dashboards and alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Companion to `ActionSummaryWireKeyContractTest` — same rationale, same shape.
 */
class MetricsWireKeyContractTest {
    @Test
    fun `footprint attribute uses the canonical wire key`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_FOOTPRINT).isEqualTo("process.memory.footprint")
    }

    @Test
    fun `superseded footprint wire key is no longer emitted`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_FOOTPRINT).isNotEqualTo("process.memory.pss")
    }

    /**
     * `process.memory.native.used` is deliberately **not** renamed to the canonical
     * `process.memory.resident`. The value behind it is
     * `MemoryMetricsReader.readNativeHeapUsedBytes()` → `Debug.getNativeHeapAllocatedSize()` —
     * native heap allocated via malloc/JNI, not resident set size (pages currently mapped into
     * physical RAM). Those are different statistics; adopting the canonical name for the wrong one
     * would make a cross-platform chart (e.g. against iOS `resident_size`) silently compare two
     * unrelated quantities. Pinned here so a future rename attempt has to either supply a real RSS
     * reading first or explicitly revisit this decision, not slip through as a "just a rename."
     */
    @Test
    fun `native heap key intentionally keeps its non-canonical name`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_NATIVE_USED).isEqualTo("process.memory.native.used")
    }

    /**
     * `process.memory.footprint` matches canonical in **unit** as well as name: canonical defines
     * footprint in bytes — the same field iOS feeds from `phys_footprint`, itself bytes — and the
     * source (`Debug.MemoryInfo.totalPss`, kB) is converted at the read site via
     * `MemoryMetricsReader.pssKbToBytes`.
     *
     * This is a value-based pin, not a method-name guard: `Debug.MemoryInfo` can't be exercised
     * with a real, known input in a JVM test (only stubbed to 0 under Robolectric, which would
     * prove nothing about the multiplication itself), so `pssKbToBytes` is tested directly instead
     * of through the full read path — it is the actual arithmetic contract, and it's pure.
     */
    @Test
    fun `footprint conversion is exactly 1024x, kB to bytes`() {
        assertThat(MemoryMetricsReader.pssKbToBytes(0L)).isEqualTo(0L)
        assertThat(MemoryMetricsReader.pssKbToBytes(1L)).isEqualTo(1024L)
        assertThat(MemoryMetricsReader.pssKbToBytes(1_024L)).isEqualTo(1_048_576L)
    }
}
