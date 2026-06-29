/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

internal class ActionSummarySpanExporter(
    private val delegate: SpanExporter,
) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        delegate.export(spans.map { addSummaryIfApplicable(it) })

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
