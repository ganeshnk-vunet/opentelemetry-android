/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.export

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.testing.trace.TestSpanData
import io.opentelemetry.sdk.trace.data.StatusData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExportDiagnosticsFormatterTest {
    @Test
    fun formatSpan_includesNameOnly() {
        val span =
            TestSpanData
                .builder()
                .setName("ui.interaction")
                .setKind(SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setHasEnded(true)
                .setStartEpochNanos(0)
                .setEndEpochNanos(1)
                .build()

        val formatted = ExportDiagnosticsFormatter.formatSpan(span)

        assertEquals("span item=ui.interaction", formatted)
        assertFalse(formatted.contains("attrs"))
    }

    @Test
    fun formatLog_includesEventNameOnly() {
        val log = mockk<LogRecordData>()
        every { log.eventName } returns "session.start"

        assertEquals("log item=session.start", ExportDiagnosticsFormatter.formatLog(log))
    }

    @Test
    fun formatLog_usesFallbackWhenEventNameMissing() {
        val log = mockk<LogRecordData>()
        every { log.eventName } returns null

        assertEquals("log item=log", ExportDiagnosticsFormatter.formatLog(log))
    }

    @Test
    fun formatMetric_includesNameOnly() {
        val metric = mockk<MetricData>()
        every { metric.name } returns "process.cpu.usage"

        assertEquals("metric item=process.cpu.usage", ExportDiagnosticsFormatter.formatMetric(metric))
    }
}
