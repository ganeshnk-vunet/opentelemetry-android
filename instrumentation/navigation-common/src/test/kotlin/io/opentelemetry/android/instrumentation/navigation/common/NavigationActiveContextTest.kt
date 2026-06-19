/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class NavigationActiveContextTest {
  private val exporter = InMemorySpanExporter.create()
  private val tracerProvider =
      SdkTracerProvider
          .builder()
          .addSpanProcessor(SimpleSpanProcessor.create(exporter))
          .build()
  private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
  private val tracer = openTelemetry.getTracer("test-navigation-active-context")
  private val emitter = NavigationSpanEmitter(tracer)

  @AfterEach
  fun tearDown() {
    NavigationSpanEmitter.clearActiveContext()
    exporter.reset()
  }

  @Test
  fun emit_activates_navigation_as_parent_for_subsequent_spans() {
    val clickSpan = tracer.spanBuilder("ui.click").startSpan()
    clickSpan.makeCurrent().use {
      emitter.emit(candidate(destinationName = "account_selection"))

      val child = tracer.spanBuilder("POST").startSpan()
      child.end()
    }
    clickSpan.end()

    val navSpan = navigationSpan()
    val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
    assertThat(childSpan.parentSpanId).isEqualTo(navSpan.spanContext.spanId)
    assertThat(navSpan.parentSpanId).isEqualTo(clickSpan.spanContext.spanId)
  }

  @Test
  fun second_emit_replaces_active_navigation_parent() {
    emitter.emit(candidate(destinationName = "login"))
    emitter.emit(candidate(destinationName = "account_selection"))

    val child = tracer.spanBuilder("POST").startSpan()
    child.end()

    val navSpans = exporter.finishedSpanItems.filter { it.name == NavigationConstants.SPAN_NAME }
    assertThat(navSpans).hasSize(2)
    val secondNav = navSpans[1]
    val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
    assertThat(childSpan.parentSpanId).isEqualTo(secondNav.spanContext.spanId)
  }

  @Test
  fun clearActiveContext_closes_navigation_scope() {
    emitter.emit(candidate(destinationName = "home"))

    NavigationSpanEmitter.clearActiveContext()

    val child = tracer.spanBuilder("POST").startSpan()
    child.end()

    val childSpan = exporter.finishedSpanItems.first { it.name == "POST" }
    assertThat(childSpan.parentSpanId).isNotEqualTo(navigationSpan().spanContext.spanId)
    assertThat(Span.current().spanContext.isValid).isFalse()
  }

  @Test
  fun post_after_navigation_parents_under_nav_within_click_trace() {
    val clickSpan = tracer.spanBuilder("ui.click").setNoParent().startSpan()
    clickSpan.makeCurrent().use {
      emitter.emit(candidate(destinationName = "account_selection"))

      val post = tracer.spanBuilder("POST account").startSpan()
      post.end()
    }
    clickSpan.end()

    val navSpan = navigationSpan()
    val postSpan = exporter.finishedSpanItems.first { it.name == "POST account" }
    assertThat(postSpan.parentSpanId).isEqualTo(navSpan.spanContext.spanId)
    assertThat(navSpan.parentSpanId).isEqualTo(clickSpan.spanContext.spanId)
    assertThat(postSpan.traceId).isEqualTo(clickSpan.spanContext.traceId)
  }

  @Test
  fun new_click_clears_navigation_and_starts_new_trace() {
    val firstClick = tracer.spanBuilder("ui.click").setNoParent().startSpan()
    firstClick.makeCurrent().use {
      emitter.emit(candidate(destinationName = "account_selection"))
    }
    firstClick.end()

    ActiveInteractionContext.clear()

    val secondClick = tracer.spanBuilder("ui.click").setNoParent().startSpan()
    secondClick.end()

    val clicks = exporter.finishedSpanItems.filter { it.name == "ui.click" }
    assertThat(clicks).hasSize(2)
    assertThat(clicks[0].traceId).isNotEqualTo(clicks[1].traceId)
  }

  private fun navigationSpan(): SpanData =
      exporter.finishedSpanItems.first { it.name == NavigationConstants.SPAN_NAME }

  private fun candidate(destinationName: String): NavigationTransitionCandidate =
      NavigationTransitionCandidate(
          source = NavigationNode(type = NavigationNodeType.COMPOSE_ROUTE, name = "login"),
          destination = NavigationNode(type = NavigationNodeType.COMPOSE_ROUTE, name = destinationName),
          transitionType = NavigationTransitionType.REPLACE,
          entryType = NavigationEntryType.INTERNAL,
          timestampNanos = 1L,
      )
}
