/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.crash

import io.opentelemetry.android.common.RumConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys** shared by the `device.crash` and `device.anr` signals.
 *
 * The behavioural tests for both signals assert through the `RumConstants` and semconv constants,
 * so they stay green if a constant's string value is reverted or mistyped — the emitted attribute
 * names are the actual contract with dashboards and alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Companion to `AppStartWireKeyContractTest` in the activity module — same rationale, same shape.
 */
class FaultWireKeyContractTest {
    @Test
    fun `error runtime uses the canonical wire key`() {
        assertThat(RumConstants.ERROR_RUNTIME_KEY.key).isEqualTo("error.runtime")
    }

    /**
     * The value space is pinned as well as the key. `error.runtime` exists to be grouped on across
     * runtimes, so a wrapper emitting `Dart` or `dartvm` instead of `dart` would break the grouping
     * without breaking anything that would fail a build. These are lowercase runtime names, not UI
     * framework names — the framework is reported separately as `app.framework`.
     */
    @Test
    fun `error runtime values are the agreed vocabulary`() {
        assertThat(RumConstants.ERROR_RUNTIME_JVM).isEqualTo("jvm")
        assertThat(RumConstants.ERROR_RUNTIME_DART).isEqualTo("dart")
        assertThat(RumConstants.ERROR_RUNTIME_JS).isEqualTo("js")
    }

    /**
     * `heap.free`, `storage.free` and `battery.percent` are emitted by both these signals and
     * `app.metrics`, and there is only **one** object behind each: `SystemMetricsSpanEmitter`
     * aliases the same constants (`ATTR_HEAP_FREE = RumConstants.HEAP_FREE_KEY`, and likewise for
     * battery and disk) rather than declaring its own.
     *
     * That matters for the canonical migration, which renames `heap.free` to
     * `process.memory.heap.free` in `app.metrics` scope only while the fault signals keep the short
     * name. Because the constant is shared, that rename cannot be applied to "the `app.metrics`
     * copy" — no such copy exists. `RumConstants.HEAP_FREE_KEY` must first be split into two
     * constants, with `app.metrics` moved onto the new one; changing the shared constant in place
     * would silently rename it here too, which is what this test exists to prevent.
     *
     * So a failure here is expected to mean the split was skipped, not that this assertion is wrong.
     */
    @Test
    fun `fault runtime detail keys keep their short names`() {
        assertThat(RumConstants.HEAP_FREE_KEY.key).isEqualTo("heap.free")
        assertThat(RumConstants.STORAGE_SPACE_FREE_KEY.key).isEqualTo("storage.free")
        assertThat(RumConstants.BATTERY_PERCENT_KEY.key).isEqualTo("battery.percent")
    }
}
