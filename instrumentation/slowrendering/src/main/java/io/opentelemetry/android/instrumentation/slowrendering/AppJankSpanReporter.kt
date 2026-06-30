/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.slowrendering

import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import java.time.Instant

// TODO: Replace with semconv constants
internal val FRAME_COUNT: AttributeKey<Long> = AttributeKey.longKey("app.jank.frame_count")
internal val PERIOD: AttributeKey<Double> = AttributeKey.doubleKey("app.jank.period")
internal val THRESHOLD: AttributeKey<Double> = AttributeKey.doubleKey("app.jank.threshold")

internal class AppJankSpanReporter(
    private val tracer: Tracer,
    private val threshold: Double,
    private val debugVerbose: Boolean = false,
) : JankReporter {
    override fun reportSlow(
        durationToCountHistogram: Map<Int, Int>,
        periodSeconds: Double,
        activityName: String,
    ) {
        var frameCount: Long = 0
        for (entry in durationToCountHistogram) {
            val durationMillis = entry.key
            if ((durationMillis / 1000.0) > threshold) {
                val count = entry.value
                if (debugVerbose || RumDiagnostics.verbose) {
                    RumDiagnostics.d { "slowRendering: slow frame ${durationMillis}ms count=$count" }
                }
                frameCount += count
            }
        }

        if (frameCount > 0) {
            val now = Instant.now()
            val attributes =
                Attributes
                    .builder()
                    .put(FRAME_COUNT, frameCount)
                    .put(PERIOD, periodSeconds)
                    .put(THRESHOLD, threshold)
                    .build()
            tracer
                .spanBuilder("app.jank")
                .setNoParent()
                .setAllAttributes(attributes)
                .setStartTimestamp(now)
                .startSpan()
                .end(now)
        }
    }
}
