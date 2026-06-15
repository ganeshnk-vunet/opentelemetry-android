/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CallTimingStateTest {
    @Test
    fun `computes phase durations in milliseconds`() {
        val state = CallTimingState()
        state.callStartNanos = 0L
        state.callEndNanos = 100_000_000L
        state.dnsStartNanos = 0L
        state.dnsEndNanos = 5_000_000L
        state.connectStartNanos = 5_000_000L
        state.connectEndNanos = 15_000_000L
        state.secureConnectStartNanos = 15_000_000L
        state.secureConnectEndNanos = 35_000_000L
        state.requestHeadersStartNanos = 35_000_000L
        state.responseHeadersStartNanos = 45_000_000L
        state.responseBodyEndNanos = 55_000_000L

        val timing = state.finalizeTiming()

        assertThat(timing.dnsMs).isEqualTo(5L)
        assertThat(timing.connectMs).isEqualTo(10L)
        assertThat(timing.tlsMs).isEqualTo(20L)
        assertThat(timing.ttfbMs).isEqualTo(10L)
        assertThat(timing.downloadMs).isEqualTo(10L)
        assertThat(timing.totalMs).isEqualTo(100L)
        assertThat(timing.phasesComplete).isTrue()
    }
}
