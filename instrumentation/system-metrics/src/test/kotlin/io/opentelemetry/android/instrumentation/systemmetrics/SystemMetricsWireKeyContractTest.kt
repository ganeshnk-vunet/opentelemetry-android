/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Locks the **structure** of the `app.metrics` span: metrics live directly on the span as
 * attributes, not nested inside a span event.
 *
 * Every other contract test in this series (`FaultWireKeyContractTest`,
 * `AppStartWireKeyContractTest`, `JankWireKeyContractTest`) pins a wire-key *string* against a
 * literal, because the risk there is a constant's value silently reverting while everything still
 * compiles. The risk here is different in shape: the emitted key names don't change, only *where*
 * they attach — so the equivalent discipline is pinning that placement directly, exercising the
 * real emitter rather than asserting on a constant.
 *
 * This existed as an `addEvent("app.metrics", buildAttributes(sample))` call until this change;
 * canonical requires metrics as span attributes, not a snapshot event, so a regression back to
 * that shape — e.g. someone re-adding `addEvent(...)` "for compatibility" alongside the new
 * `setAllAttributes(...)` call, or the reverse omission that leaves the data attached nowhere —
 * is exactly what the two assertions below are built to catch. `SystemMetricsSpanEmitterTest`
 * already exercises every attribute value; this file's job is the structural claim alone.
 */
class SystemMetricsWireKeyContractTest {
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var openTelemetry: OpenTelemetrySdk

    @BeforeEach
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
        openTelemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
    }

    @Test
    fun `app-metrics span carries metrics as attributes, not as an event`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val emitter =
            SystemMetricsSpanEmitter(
                openTelemetry = openTelemetry,
                scheduler = scheduler,
                intervalSeconds = 2L,
                deviceReader = StubDeviceMetricsReader(),
            )

        emitter.start()
        scheduler.awaitTermination(3_500, TimeUnit.MILLISECONDS)
        scheduler.shutdownNow()

        val metricsSpan = spanExporter.finishedSpanItems.first { it.name == "app.metrics" }

        // The structural claim: no snapshot event at all.
        assertThat(metricsSpan.events).isEmpty()

        // A representative spread across the metric families, proving the data landed on the
        // span itself rather than nowhere. Full 14-key coverage lives in
        // SystemMetricsSpanEmitterTest — this is enough to prove the claim isn't vacuous.
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_USAGE)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_HEAP_USED)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_SYS_MEM_AVAILABLE)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_BATTERY_LEVEL)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_DISK_FREE)).isNotNull
    }
}
