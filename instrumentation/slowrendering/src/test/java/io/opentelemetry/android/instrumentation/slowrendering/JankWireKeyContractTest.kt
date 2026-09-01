/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.slowrendering

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys** and value vocabulary of the `app.jank` span.
 *
 * The behavioural tests in this package assert through the `FRAME_COUNT` / `PERIOD` / `THRESHOLD` /
 * `JANK_TYPE` constants, so they stay green if a constant's string value is reverted or mistyped —
 * the emitted names are the actual contract with dashboards and alerts, and nothing else pins them
 * down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Companion to `AppStartWireKeyContractTest` and `FaultWireKeyContractTest` — same rationale,
 * same shape.
 */
class JankWireKeyContractTest {
    @Test
    fun `jank attributes use the canonical wire keys`() {
        assertThat(FRAME_COUNT.key).isEqualTo("app.jank.frame_count")
        assertThat(PERIOD.key).isEqualTo("app.jank.period")
        assertThat(THRESHOLD.key).isEqualTo("app.jank.threshold")
        assertThat(JANK_TYPE.key).isEqualTo("app.jank.type")
    }

    /**
     * The values are as much of the contract as the key: a consumer groups by them to separate slow
     * frames from frozen ones. Pinned so a rename to `SLOW` / `Frozen` — which nothing else in the
     * build would catch — fails here.
     */
    @Test
    fun `jank type values are the agreed vocabulary`() {
        assertThat(JANK_TYPE_SLOW).isEqualTo("slow")
        assertThat(JANK_TYPE_FROZEN).isEqualTo("frozen")
    }

    /**
     * The thresholds reach the wire as `app.jank.threshold` (in seconds), so they are part of the
     * emitted contract, not just internal tuning: until `app.jank.type` existed they were the only
     * way to tell the two buckets apart, and existing dashboards filter on `0.016` / `0.7`.
     * Changing either silently reclassifies historical data against new.
     */
    @Test
    fun `jank thresholds keep the values consumers filter on`() {
        assertThat(SLOW_THRESHOLD_MS).isEqualTo(16)
        assertThat(FROZEN_THRESHOLD_MS).isEqualTo(700)
        assertThat(SLOW_THRESHOLD_MS / 1000.0).isEqualTo(0.016)
        assertThat(FROZEN_THRESHOLD_MS / 1000.0).isEqualTo(0.7)
    }
}
