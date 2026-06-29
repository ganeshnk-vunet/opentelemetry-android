/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

internal class ActionSummarySpanExporter(
    private val delegate: SpanExporter,
) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        delegate.export(enrich(spans))

    private fun enrich(spans: Collection<SpanData>): Collection<SpanData> {
        var result: ArrayList<SpanData>? = null
        var index = 0
        for (span in spans) {
            val enriched = addSummaryIfApplicable(span)
            if (result == null && enriched !== span) {
                result =
                    ArrayList<SpanData>(spans.size).apply {
                        for (prior in spans) {
                            if (size == index) break
                            add(prior)
                        }
                    }
            }
            result?.add(enriched)
            index++
        }
        return result ?: spans
    }

    private fun addSummaryIfApplicable(span: SpanData): SpanData {
        val summary = ActionSummarizer.summarize(span) ?: return span
        val newAttributes =
            span.attributes.toBuilder()
                .put(RumConstants.APP_ACTION_SUMMARY_KEY, summary)
                .build()
        return ModifiedSpanData(span, newAttributes)
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}
