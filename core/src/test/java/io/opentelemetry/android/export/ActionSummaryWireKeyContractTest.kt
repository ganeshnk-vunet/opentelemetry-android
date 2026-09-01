/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire key** written by [ActionSummarySpanExporter].
 *
 * `ActionSummarySpanExporterTest` asserts through [RumConstants.APP_ACTION_SUMMARY_KEY], so it
 * stays green if the constant's string value is reverted or mistyped — the emitted name is the
 * actual contract with dashboards and alerts, and nothing else pins it down.
 *
 * The expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Companion to `AppStartWireKeyContractTest`, `FaultWireKeyContractTest` and
 * `JankWireKeyContractTest` — same rationale, same shape.
 */
class ActionSummaryWireKeyContractTest {
    @Test
    fun `action summary uses the canonical wire key`() {
        assertThat(RumConstants.APP_ACTION_SUMMARY_KEY.key).isEqualTo("semantic.summary")
    }

    /**
     * The constant identifier stays `APP_ACTION_SUMMARY_KEY` even though the wire key no longer
     * matches it. Renaming the identifier would churn `common/api/common.api` and break Java
     * callers for no gain, since only the emitted string is the consumer-facing contract. Pinned
     * so the mismatch reads as deliberate rather than as something half-renamed.
     */
    @Test
    fun `superseded wire key is no longer emitted`() {
        assertThat(RumConstants.APP_ACTION_SUMMARY_KEY.key).isNotEqualTo("app.action.summary")
    }
}
