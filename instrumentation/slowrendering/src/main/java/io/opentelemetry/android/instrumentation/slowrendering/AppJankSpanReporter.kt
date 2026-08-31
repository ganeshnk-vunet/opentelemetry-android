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

/**
 * Which jank bucket a span reports.
 *
 * Without this, the only thing separating a slow-frame span from a frozen-frame one is the value
 * of [THRESHOLD] (0.016 vs 0.7), so every consumer has to match on a float. It matters more than it
 * looks because the buckets are cumulative — a frozen frame exceeds both thresholds and so is
 * counted in both spans — which makes "slow but not frozen" a subtraction between two
 * float-identified sets rather than a group-by.
 */
internal val JANK_TYPE: AttributeKey<String> = AttributeKey.stringKey("app.jank.type")

internal const val JANK_TYPE_SLOW = "slow"
internal const val JANK_TYPE_FROZEN = "frozen"

internal class AppJankSpanReporter(
    private val tracer: Tracer,
    private val threshold: Double,
    /**
     * Passed in rather than derived from [threshold]. Inferring it (`if (threshold >= 0.7)`) would
     * rebuild the same magic-number coupling to the threshold value that [JANK_TYPE] exists to
     * remove, and would silently mislabel any future bucket.
     */
    private val jankType: String,
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
                    .put(JANK_TYPE, jankType)
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
