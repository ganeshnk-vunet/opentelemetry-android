/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.slowrendering

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.jupiter.api.Test

class AppJankSpanReporterTest {
    @Rule
    var otelTesting: OpenTelemetryRule = OpenTelemetryRule.create()

    @Test
    fun `span is generated`() {
        val tracer = otelTesting.openTelemetry.getTracer("JANK!")
        val jankReporter = AppJankSpanReporter(tracer, 0.600)
        val histogramData = HashMap<Int, Int>()
        histogramData[17] = 3
        histogramData[701] = 1

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        jankReporter.reportSlow(histogramData, 10.5, "io.otel/Komponent")

        assertThat(otelTesting.spans.size).isEqualTo(1)
        val span = otelTesting.spans.get(0)
        assertThat(span.name).isEqualTo("app.jank")
        assertThat(span.attributes.get(FRAME_COUNT)).isEqualTo(1)
        assertThat(span.attributes.get(PERIOD)).isEqualTo(10.5)
        assertThat(span.attributes.get(THRESHOLD)).isEqualTo(0.6)
    }

    @Test
    fun `span has no parent even when an ambient span is active`() {
        val tracer = otelTesting.openTelemetry.getTracer("JANK!")
        val jankReporter = AppJankSpanReporter(tracer, 0.600)
        val histogramData = HashMap<Int, Int>()
        histogramData[701] = 1

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        val parent = tracer.spanBuilder("activity.lifecycle").startSpan()
        val scope = parent.makeCurrent()
        try {
            jankReporter.reportSlow(histogramData, 10.5, "io.otel/Komponent")
        } finally {
            scope.close()
            parent.end()
        }

        val jankSpan = otelTesting.spans.first { it.name == "app.jank" }
        assertThat(jankSpan.parentSpanContext.isValid).isFalse()
    }
}
