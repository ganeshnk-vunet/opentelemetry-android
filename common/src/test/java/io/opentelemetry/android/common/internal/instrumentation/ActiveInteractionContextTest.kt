/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
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
    fun activate_updates_active_span_for_parentContextOr() {
        val parent = tracer.spanBuilder("ui.navigation").startSpan()
        parent.end()
        ActiveInteractionContext.activate(parent)

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(parent.spanContext.spanId)
    }

    @Test
    fun clear_resets_active_span() {
        val parent = tracer.spanBuilder("ui.navigation").startSpan()
        parent.end()
        ActiveInteractionContext.activate(parent)

        ActiveInteractionContext.clear()

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isNotEqualTo(parent.spanContext.spanId)
    }

    @Test
    fun second_activate_replaces_previous_active_span() {
        val first = tracer.spanBuilder("ui.navigation").setAttribute("screen", "login").startSpan()
        first.end()
        ActiveInteractionContext.activate(first)

        val second = tracer.spanBuilder("ui.navigation").setAttribute("screen", "home").startSpan()
        second.end()
        ActiveInteractionContext.activate(second)

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(second.spanContext.spanId)
    }

    @Test
    fun begin_sets_active_span_and_root_context() {
        val click = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val token = ActiveInteractionContext.begin(click)
        click.end()

        assertThat(token).isGreaterThan(0)
        assertThat(ActiveInteractionContext.rootContext()).isNotNull()

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(click.spanContext.spanId)
    }

    @Test
    fun end_current_token_clears_interaction() {
        val click = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val token = ActiveInteractionContext.begin(click)
        click.end()

        ActiveInteractionContext.end(token)

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isNotEqualTo(click.spanContext.spanId)
    }

    @Test
    fun end_stale_token_is_no_op() {
        val click = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val token = ActiveInteractionContext.begin(click)
        click.end()

        val nav = tracer.spanBuilder("ui.navigation").startSpan()
        nav.end()
        ActiveInteractionContext.activate(nav)

        ActiveInteractionContext.end(token - 1)

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val child = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        child.end()

        val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(childSpan.parentSpanId).isEqualTo(nav.spanContext.spanId)
    }

    @Test
    fun begin_twice_replaces_root_context() {
        val firstClick = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val firstToken = ActiveInteractionContext.begin(firstClick)
        val firstRoot = ActiveInteractionContext.rootContext()
        firstClick.end()

        val secondClick = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val secondToken = ActiveInteractionContext.begin(secondClick)

        assertThat(secondToken).isNotEqualTo(firstToken)
        assertThat(ActiveInteractionContext.rootContext()).isNotEqualTo(firstRoot)

        ActiveInteractionContext.end(secondToken)
        secondClick.end()
    }

    @Test
    fun interaction_end_prevents_cross_interaction_leakage() {
        val click = tracer.spanBuilder("ui.interaction").setNoParent().startSpan()
        val token = ActiveInteractionContext.begin(click)
        click.end()

        val nav = tracer.spanBuilder("ui.navigation").startSpan()
        nav.end()
        ActiveInteractionContext.activate(nav)

        ActiveInteractionContext.end(token)

        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        val orphan = tracer.spanBuilder("POST").setParent(parentContext).startSpan()
        orphan.end()

        val orphanSpan = exporter.finishedSpanItems.first { it.name == "POST" }
        assertThat(orphanSpan.parentSpanId).isNotEqualTo(nav.spanContext.spanId)
        assertThat(orphanSpan.parentSpanId).isNotEqualTo(click.spanContext.spanId)
    }

    @Test
    fun parentContextOr_returns_current_when_exporter_context() {
        val active = tracer.spanBuilder("ui.interaction").startSpan()
        ActiveInteractionContext.activate(active)

        val current = Context.root()
        val exporterContext = ExporterMarker.markExporter(current)

        val result = ActiveInteractionContext.parentContextOr(exporterContext)
        assertThat(result).isEqualTo(exporterContext)
    }

    @Test
    fun parentContextOr_upgrades_same_trace() {
        val click = tracer.spanBuilder("ui.interaction").startSpan()
        val nav = tracer.spanBuilder("ui.navigation").setParent(Context.current().with(click)).startSpan()
        ActiveInteractionContext.activate(nav)

        val current = Context.current().with(click)
        val result = ActiveInteractionContext.parentContextOr(current)

        val resultSpan = Span.fromContext(result)
        assertThat(resultSpan.spanContext.spanId).isEqualTo(nav.spanContext.spanId)
    }

    @Test
    fun parentContextOr_does_not_override_different_trace() {
        val click = tracer.spanBuilder("ui.interaction").startSpan()
        val nav = tracer.spanBuilder("ui.navigation").startSpan() // different trace
        ActiveInteractionContext.activate(nav)

        val current = Context.current().with(click)
        val result = ActiveInteractionContext.parentContextOr(current)

        val resultSpan = Span.fromContext(result)
        assertThat(resultSpan.spanContext.spanId).isEqualTo(click.spanContext.spanId)
    }
}
