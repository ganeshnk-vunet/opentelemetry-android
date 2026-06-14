/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports trace spans with a minimal [Resource] by default. The first exported cold [app.start][RumConstants.APP_START_SPAN_NAME]
 * span per process is upgraded to [fullResource].
 */
internal class SelectiveResourceSpanExporter(
    private val delegate: SpanExporter,
    private val fullResource: Resource,
) : SpanExporter {
    private val firstColdAppStartExported = AtomicBoolean(false)

    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        delegate.export(
            spans.map { span ->
                if (shouldUpgradeToFullResource(span)) {
                    ResourceOverrideSpanData(span, fullResource)
                } else {
                    span
                }
            },
        )

    private fun shouldUpgradeToFullResource(span: SpanData): Boolean {
        if (span.name != RumConstants.APP_START_SPAN_NAME) {
            return false
        }
        if (span.attributes.get(RumConstants.START_TYPE_KEY) != "cold") {
            return false
        }
        return firstColdAppStartExported.compareAndSet(false, true)
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}
