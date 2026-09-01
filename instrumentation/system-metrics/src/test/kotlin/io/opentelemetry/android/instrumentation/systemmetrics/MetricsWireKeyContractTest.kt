/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys** of the two `app.metrics` attributes renamed to their canonical names.
 *
 * `SystemMetricsSpanEmitterTest` asserts through `ATTR_NATIVE_USED` / `ATTR_PSS_KB`, so it stays
 * green if a constant's string value is reverted or mistyped — the emitted attribute names are the
 * actual contract with dashboards and alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Companion to `AppStartWireKeyContractTest`, `FaultWireKeyContractTest`,
 * `JankWireKeyContractTest` and `ActionSummaryWireKeyContractTest` — same rationale, same shape.
 */
class MetricsWireKeyContractTest {
    @Test
    fun `renamed process memory attributes use the canonical wire keys`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_NATIVE_USED).isEqualTo("process.memory.resident")
        assertThat(SystemMetricsSpanEmitter.METRIC_PSS_KB).isEqualTo("process.memory.footprint")
    }

    @Test
    fun `superseded wire keys are no longer emitted`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_NATIVE_USED).isNotEqualTo("process.memory.native.used")
        assertThat(SystemMetricsSpanEmitter.METRIC_PSS_KB).isNotEqualTo("process.memory.pss")
    }

    /**
     * `process.memory.footprint` reached the canonical name in this change, but not the canonical
     * *unit* — canonical defines footprint in bytes, while the value comes from
     * `MemoryMetricsReader.readPssKb()`, whose own name and KDoc say kB (backed by
     * `Debug.MemoryInfo.totalPss`, which Android itself documents in kB). Converting the unit is a
     * separate, deliberate decision: it would silently move every existing value by 1024x.
     *
     * This can't be asserted at runtime without Robolectric and a real device memory read, so it's
     * pinned as a method-name guard instead: if `readPssKb` is ever renamed away from `Kb` as part
     * of a unit conversion, this is the reminder to update `METRIC_PSS_KB`'s CHANGELOG/README unit
     * notes in the same change rather than let them go stale.
     */
    @Test
    fun `pss reader still declares itself kB-denominated`() {
        val declaresKb =
            MemoryMetricsReader::class.java.methods.any { it.name == "readPssKb" }
        assertThat(declaresKb)
            .`as`("MemoryMetricsReader.readPssKb() was renamed — update the footprint unit notes")
            .isTrue()
    }
}
