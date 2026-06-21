/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

object ExporterMarker {
    private val KEY = ContextKey.named<Boolean>("otel-exporter-marker")

    fun isExporterContext(context: Context): Boolean {
        return context.get(KEY) == true
    }

    fun markExporter(context: Context): Context {
        return context.with(KEY, true)
    }
}

class MarkerSpanExporter(private val delegate: SpanExporter) : SpanExporter by delegate {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.export(spans)
        }
    }

    override fun flush(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.flush()
        }
    }

    override fun shutdown(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.shutdown()
        }
    }
}

class MarkerLogRecordExporter(private val delegate: LogRecordExporter) : LogRecordExporter by delegate {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.export(logs)
        }
    }

    override fun flush(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.flush()
        }
    }

    override fun shutdown(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.shutdown()
        }
    }
}

class MarkerMetricExporter(private val delegate: MetricExporter) : MetricExporter by delegate {
    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.export(metrics)
        }
    }

    override fun flush(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.flush()
        }
    }

    override fun shutdown(): CompletableResultCode {
        return ExporterMarker.markExporter(Context.current()).makeCurrent().use {
            delegate.shutdown()
        }
    }
}
