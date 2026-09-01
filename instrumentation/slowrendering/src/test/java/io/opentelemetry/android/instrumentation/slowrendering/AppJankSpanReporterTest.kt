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
        val jankReporter = AppJankSpanReporter(tracer, 0.600, JANK_TYPE_FROZEN)
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
        assertThat(span.attributes.get(JANK_TYPE)).isEqualTo("frozen")
    }

    /**
     * The buckets are cumulative: 3×17ms + 1×701ms yields slow `frame_count=4` and frozen
     * `frame_count=1`. Span-count subtraction (`1 - 1`) would be 0; the documented slow-only
     * equivalent is `sum(frame_count)` slow minus frozen (`4 - 1 = 3`).
     */
    @Test
    fun `slow reporter frame_count includes frozen frames`() {
        val tracer = otelTesting.openTelemetry.getTracer("JANK!")
        val histogramData = HashMap<Int, Int>()
        histogramData[17] = 3
        histogramData[701] = 1

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        AppJankSpanReporter.combined(tracer).reportSlow(histogramData, 1.0, "io.otel/Komponent")

        val byType =
            otelTesting.spans
                .filter { it.name == "app.jank" }
                .associate { it.attributes.get(JANK_TYPE) to it }
        assertThat(byType.keys).containsExactlyInAnyOrder("slow", "frozen")
        assertThat(byType["slow"]!!.attributes.get(FRAME_COUNT)).isEqualTo(4)
        assertThat(byType["slow"]!!.attributes.get(THRESHOLD)).isEqualTo(0.016)
        assertThat(byType["frozen"]!!.attributes.get(FRAME_COUNT)).isEqualTo(1)
        assertThat(byType["frozen"]!!.attributes.get(THRESHOLD)).isEqualTo(0.7)
    }

    @Test
    fun `span has no parent even when an ambient span is active`() {
        val tracer = otelTesting.openTelemetry.getTracer("JANK!")
        val jankReporter = AppJankSpanReporter(tracer, 0.600, JANK_TYPE_FROZEN)
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
