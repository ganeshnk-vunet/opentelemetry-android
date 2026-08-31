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
     * The buckets are cumulative: the 701ms frame here exceeds both thresholds, so it is reported
     * by both reporters and appears in both spans. That is why `app.jank.type` is needed —
     * a consumer counting jank spans would otherwise double-count frozen frames, with only the
     * `app.jank.threshold` float to tell the two apart.
     */
    @Test
    fun `slow and frozen reporters label the same frame differently`() {
        val tracer = otelTesting.openTelemetry.getTracer("JANK!")
        val histogramData = HashMap<Int, Int>()
        histogramData[701] = 1

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        AppJankSpanReporter(tracer, SLOW_THRESHOLD_MS / 1000.0, JANK_TYPE_SLOW)
            .reportSlow(histogramData, 1.0, "io.otel/Komponent")
        AppJankSpanReporter(tracer, FROZEN_THRESHOLD_MS / 1000.0, JANK_TYPE_FROZEN)
            .reportSlow(histogramData, 1.0, "io.otel/Komponent")

        val types = otelTesting.spans.filter { it.name == "app.jank" }.map { it.attributes.get(JANK_TYPE) }
        assertThat(types).containsExactlyInAnyOrder("slow", "frozen")
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
