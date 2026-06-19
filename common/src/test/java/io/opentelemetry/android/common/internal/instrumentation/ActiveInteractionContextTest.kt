/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ActiveInteractionContextTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
    private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    private val tracer = openTelemetry.getTracer("test-active-interaction-context")

    @AfterEach
    fun tearDown() {
        ActiveInteractionContext.clear()
        exporter.reset()
    }

    @Test
    fun activate_makes_span_current_for_downstream_spans() {
        val parent = tracer.spanBuilder("ui.navigation").startSpan()
        parent.end()
        ActiveInteractionContext.activate(parent)

        val child = tracer.spanBuilder("POST").startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(parent.spanContext.spanId)
    }

    @Test
    fun clear_closes_active_scope() {
        val parent = tracer.spanBuilder("ui.navigation").startSpan()
        parent.end()
        ActiveInteractionContext.activate(parent)

        ActiveInteractionContext.clear()

        val child = tracer.spanBuilder("POST").startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isNotEqualTo(parent.spanContext.spanId)
        assertThat(Span.current().spanContext.isValid).isFalse()
    }

    @Test
    fun second_activate_replaces_previous_scope() {
        val first = tracer.spanBuilder("ui.navigation").setAttribute("screen", "login").startSpan()
        first.end()
        ActiveInteractionContext.activate(first)

        val second = tracer.spanBuilder("ui.navigation").setAttribute("screen", "home").startSpan()
        second.end()
        ActiveInteractionContext.activate(second)

        val child = tracer.spanBuilder("POST").startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(second.spanContext.spanId)
    }
}
