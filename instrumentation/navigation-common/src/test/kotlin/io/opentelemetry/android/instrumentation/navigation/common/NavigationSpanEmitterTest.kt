/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class NavigationSpanEmitterTest {
    @AfterEach
    fun tearDown() {
        NavigationSpanEmitter.clearActiveContext()
    }

    @Test
    fun emits_navigation_span_with_expected_attributes() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val emitter = NavigationSpanEmitter(openTelemetry.getTracer("test-navigation-common"))

        emitter.emit(
            NavigationTransitionCandidate(
                source = NavigationNode(type = NavigationNodeType.ACTIVITY, name = "Home"),
                destination = NavigationNode(type = NavigationNodeType.FRAGMENT, name = "Details"),
                transitionType = NavigationTransitionType.PUSH,
                entryType = NavigationEntryType.INTERNAL,
                timestampNanos = 1234L,
            ),
        )

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY)).isEqualTo("fragment")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY)).isEqualTo("Details")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY)).isEqualTo("internal")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TIMESTAMP_NS_KEY)).isEqualTo(1234L)
        assertThat(spans[0].attributes.get(SCREEN_NAME_KEY)).isEqualTo("Details")
    }

    @Test
    fun supports_compose_route_destination_type() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val emitter = NavigationSpanEmitter(openTelemetry.getTracer("test-navigation-common"))

        emitter.emit(
            NavigationTransitionCandidate(
                source = null,
                destination = NavigationNode(type = NavigationNodeType.COMPOSE_ROUTE, name = "details/{id}"),
                transitionType = NavigationTransitionType.PUSH,
                entryType = NavigationEntryType.INTERNAL,
                timestampNanos = 42L,
            ),
        )

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY)).isEqualTo("compose_route")
    }

    @Test
    fun emits_navigation_trigger_when_provided() {
        val exporter = InMemorySpanExporter.create()
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val emitter = NavigationSpanEmitter(openTelemetry.getTracer("test-navigation-common"))

        emitter.emit(
            candidate =
                NavigationTransitionCandidate(
                    source = NavigationNode(type = NavigationNodeType.COMPOSE_ROUTE, name = "home"),
                    destination = NavigationNode(type = NavigationNodeType.COMPOSE_ROUTE, name = "details"),
                    transitionType = NavigationTransitionType.POP,
                    entryType = NavigationEntryType.INTERNAL,
                    timestampNanos = 99L,
                ),
            navigationTrigger = "back_press",
        )

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }
}
