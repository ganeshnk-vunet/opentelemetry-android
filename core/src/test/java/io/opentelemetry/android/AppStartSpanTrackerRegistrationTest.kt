/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the tracker through a real [SdkTracerProvider] rather than a mocked
 * span, so the wiring is covered end to end: a unit test driving the processor
 * directly stays green even if the processor is never registered, or if the SDK
 * stops delivering the hooks it depends on.
 */
internal class AppStartSpanTrackerRegistrationTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(AppStartSpanTracker())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

    @BeforeEach
    @AfterEach
    fun reset() {
        AppStartSpans.clear()
        exporter.reset()
    }

    @Test
    fun `a real app start span is published while live and cleared once ended`() {
        val tracer = tracerProvider.get("test")
        val span = tracer.spanBuilder(RumConstants.APP_START_SPAN_NAME).startSpan()

        assertThat(AppStartSpans.current?.spanContext).isEqualTo(span.spanContext)

        span.end()

        assertThat(AppStartSpans.current).isNull()
        assertThat(exporter.finishedSpanItems).hasSize(1)
    }

    @Test
    fun `other spans never populate the global`() {
        val tracer = tracerProvider.get("test")

        tracer.spanBuilder("ui.navigation").startSpan().end()

        assertThat(AppStartSpans.current).isNull()
    }

    /** A wrapper writing while the span is live must reach the exported span. */
    @Test
    fun `events written through the published span are exported`() {
        val tracer = tracerProvider.get("test")
        val span = tracer.spanBuilder(RumConstants.APP_START_SPAN_NAME).startSpan()

        AppStartSpans.current?.addEvent("flutter.dart_entry")
        span.end()

        val events = exporter.finishedSpanItems.single().events
        assertThat(events.map { it.name }).contains("flutter.dart_entry")
    }
}
