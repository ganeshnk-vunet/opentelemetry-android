/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.dsl

import io.opentelemetry.android.Incubating
import io.opentelemetry.android.OtelAndroidClock
import io.opentelemetry.android.agent.dsl.instrumentation.InstrumentationConfiguration
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.instrumentation.AndroidInstrumentationLoader
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.ResourceBuilder
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Type-safe config DSL that controls how OpenTelemetry should behave.
 */
@OptIn(Incubating::class)
@OpenTelemetryDslMarker
class OpenTelemetryConfiguration internal constructor(
    internal val rumConfig: OtelRumConfig = OtelRumConfig(),
    internal val diskBufferingConfig: DiskBufferingConfigurationSpec = DiskBufferingConfigurationSpec(rumConfig),
    /**
     * Configures the [Clock] used for capturing telemetry.
     */
    var clock: Clock = OtelAndroidClock(),
    instrumentationLoader: AndroidInstrumentationLoader,
) {
    internal val exportConfig = HttpExportConfiguration()
    internal val sessionConfig = SessionConfiguration()
    internal val instrumentations = InstrumentationConfiguration(rumConfig, instrumentationLoader)
    internal var resourceAction: ResourceBuilder.() -> Unit = {}
    internal var spanExporterCustomizerAction: (SpanExporter) -> SpanExporter = { it }
    internal var logRecordExporterCustomizerAction: (LogRecordExporter) -> LogRecordExporter = { it }

    /**
     * When true, enables verbose diagnostics across instrumentations and OTLP export decorators.
     */
    var diagnosticLogging: Boolean = false

    /**
     * Configures how OpenTelemetry should export telemetry over HTTP.
     */
    fun httpExport(action: HttpExportConfiguration.() -> Unit) {
        exportConfig.action()
    }

    /**
     * Configures individual instrumentations.
     */
    fun instrumentations(action: InstrumentationConfiguration.() -> Unit) {
        instrumentations.action()
    }

    /**
     * Configures session behavior.
     */
    fun session(action: SessionConfiguration.() -> Unit) {
        sessionConfig.action()
    }

    /**
     * Configures attributes that are used globally. Use this method
     * if your attributes don't change over time.
     */
    fun globalAttributes(action: () -> Attributes) {
        rumConfig.setGlobalAttributes(action())
    }

    /**
     * Configures attributes that are used globally. Use this method
     * to supply attributes that might change over time.
     */
    fun globalAttributesSupplier(action: () -> (() -> Attributes)) {
        rumConfig.setGlobalAttributes(action())
    }

    /**
     * Configures disk buffering behavior of exported telemetry.
     */
    fun diskBuffering(action: DiskBufferingConfigurationSpec.() -> Unit) {
        diskBufferingConfig.action()
    }

    /**
     * Configures the resource attributes that are used globally by acting on a [ResourceBuilder].
     */
    fun resource(action: ResourceBuilder.() -> Unit) {
        resourceAction = action
    }

    /**
     * Wraps the OTLP span exporter after diagnostic decorators are applied.
     * Use for export-time filtering or enrichment of finished spans.
     */
    fun spanExporterCustomizer(action: (SpanExporter) -> SpanExporter) {
        spanExporterCustomizerAction = action
    }

    /**
     * Wraps the OTLP log record exporter after diagnostic decorators are applied.
     */
    fun logRecordExporterCustomizer(action: (LogRecordExporter) -> LogRecordExporter) {
        logRecordExporterCustomizerAction = action
    }
}
