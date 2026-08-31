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
    fun `error runtime uses the canonical wire key and value`() {
        assertThat(RumConstants.ERROR_RUNTIME_KEY.key).isEqualTo("error.runtime")
        assertThat(RumConstants.ERROR_RUNTIME_JVM).isEqualTo("jvm")
    }

    /**
     * `heap.free`, `storage.free` and `battery.percent` are deliberately shared with the
     * `app.metrics` signal so the two schemas line up. Canonical renames only the `app.metrics`
     * copy of `heap.free`; the fault signals keep the short names. Pinned here so that migration
     * cannot quietly drag these along with it.
     */
    @Test
    fun `fault runtime detail keys keep their short names`() {
        assertThat(RumConstants.HEAP_FREE_KEY.key).isEqualTo("heap.free")
        assertThat(RumConstants.STORAGE_SPACE_FREE_KEY.key).isEqualTo("storage.free")
        assertThat(RumConstants.BATTERY_PERCENT_KEY.key).isEqualTo("battery.percent")
    }
}
