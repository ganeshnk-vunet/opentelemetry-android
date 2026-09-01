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
 * Stub DeviceMetricsReader that avoids real Android context dependencies.
 */
internal class StubDeviceMetricsReader : DeviceMetricsReader {
    override fun readDeviceMemoryInfo() = DeviceMemoryInfo(1_000_000L, 500_000L, 0L)
    override fun readBatteryInfo() = BatteryInfo(75.0, 30.0)
    override fun readDiskInfo() = DiskInfo(10_000_000L, 100_000_000L)
}

class SystemMetricsSpanEmitterTest {
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracerProvider: SdkTracerProvider
    private lateinit var openTelemetry: OpenTelemetrySdk

    @BeforeEach
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        tracerProvider =
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
    fun `emits standalone app-metrics span`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val emitter =
            SystemMetricsSpanEmitter(
                openTelemetry = openTelemetry,
                scheduler = scheduler,
                intervalSeconds = 2L,
                deviceReader = StubDeviceMetricsReader(),
            )

        emitter.start()
        // sub-sampler fires at t=1s, emitter fires at t=2s — window has ≥1 sample
        scheduler.awaitTermination(3_500, TimeUnit.MILLISECONDS)
        scheduler.shutdownNow()

        val spans = spanExporter.finishedSpanItems
        assertThat(spans).isNotEmpty
        val metricsSpan = spans.first { it.name == "app.metrics" }
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_USAGE)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_MIN)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_MAX)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_HEAP_USED)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_THREAD_COUNT)).isNotNull
    }

    @Test
    fun `standalone span carries all process and device metric attributes including min and max`() {
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

        // Process attrs — current values
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_USAGE)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_HEAP_USED)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_HEAP_ALLOCATED)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_HEAP_FREE)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_NATIVE_USED)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_THREAD_COUNT)).isNotNull

        // CPU min/max window attrs
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_MIN)).isNotNull
        assertThat(metricsSpan.attributes.get(SystemMetricsSpanEmitter.ATTR_CPU_MAX)).isNotNull
    }
}
