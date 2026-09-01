/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import io.opentelemetry.android.common.RumConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the `heap.free` split between `app.metrics` and `device.crash`.
 *
 * Canonical renames this field to `process.memory.heap.free` on `app.metrics` **only** — the fault
 * signals keep the short `heap.free`. Before this change both signals emitted through a single
 * object ([RumConstants.HEAP_FREE_KEY]), so the rename was impossible to express: changing the
 * shared constant would have silently renamed it on `device.crash` too.
 *
 * The split is therefore the contract, not just the new name, and it is invisible to every other
 * test — `SystemMetricsSpanEmitterTest` asserts through [SystemMetricsSpanEmitter.ATTR_HEAP_FREE]
 * and `RuntimeDetailsExtractorTest` through [RumConstants.HEAP_FREE_KEY], so re-aliasing the two
 * back together would leave both green.
 *
 * Expected values are string literals rather than references to the constants they pin; referring
 * to the constants would reintroduce exactly the blind spot this test exists to close.
 */
class HeapFreeSplitContractTest {
    @Test
    fun `app metrics heap free uses the canonical wire key`() {
        assertThat(SystemMetricsSpanEmitter.METRIC_HEAP_FREE).isEqualTo("process.memory.heap.free")
        assertThat(SystemMetricsSpanEmitter.ATTR_HEAP_FREE.key).isEqualTo("process.memory.heap.free")
    }

    /**
     * The crash side is the half that must *not* move. `device.crash` and `device.anr` keep
     * `heap.free`, alongside `storage.free` and `battery.percent`, which canonical leaves unchanged
     * on both signals and which therefore still legitimately share one constant.
     */
    @Test
    fun `crash schema keys keep their short names`() {
        assertThat(RumConstants.HEAP_FREE_KEY.key).isEqualTo("heap.free")
        assertThat(RumConstants.STORAGE_SPACE_FREE_KEY.key).isEqualTo("storage.free")
        assertThat(RumConstants.BATTERY_PERCENT_KEY.key).isEqualTo("battery.percent")
    }

    /**
     * The actual regression this file exists to prevent: someone "simplifying" the local key back
     * into an alias of the shared one, which would compile, pass every other test, and silently
     * revert `app.metrics` to `heap.free`.
     */
    @Test
    fun `metrics and crash heap free are separate keys`() {
        assertThat(SystemMetricsSpanEmitter.ATTR_HEAP_FREE.key)
            .`as`("app.metrics heap-free must not be re-aliased to the crash key")
            .isNotEqualTo(RumConstants.HEAP_FREE_KEY.key)
    }

    /**
     * Battery and disk still alias `RumConstants` on purpose. Pinned so the split above reads as a
     * targeted change rather than the start of unpicking every shared key.
     */
    @Test
    fun `battery and disk remain aliases of the shared crash keys`() {
        assertThat(SystemMetricsSpanEmitter.ATTR_BATTERY_LEVEL).isSameAs(RumConstants.BATTERY_PERCENT_KEY)
        assertThat(SystemMetricsSpanEmitter.ATTR_DISK_FREE).isSameAs(RumConstants.STORAGE_SPACE_FREE_KEY)
    }
}
