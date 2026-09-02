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
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.api.trace.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NavigationSpanEmitterTest {
    /**
     * `navigation.is_initial` is backed by process-global state, so without this reset the first
     * test to run would be the only one seeing `true` and the assertions below would depend on
     * execution order.
     */
    @BeforeEach
    fun resetColdStart() {
        NavigationColdStartTracker.resetForTesting()
    }

    /**
     * Both pieces of state read by the emitter are process-global, so anything left set here leaks
     * into the next test and into later classes in this module.
     *
     * `clearActiveContext()` already reaches [ActiveInteractionContext] transitively, but the click
     * window is owned by hybrid-click rather than by navigation, so it is cleared explicitly — a
     * reader should not have to follow `NavigationActiveContext` to see that
     * `does_not_report_user_tap_without_a_click_interaction` is protected. Removing either line
     * makes that test fail.
     */
    @AfterEach
    fun tearDown() {
        NavigationSpanEmitter.clearActiveContext()
        ActiveInteractionContext.clear()
        NavigationColdStartTracker.resetForTesting()
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

    @Test
    fun reports_is_initial_only_for_the_first_navigation_of_the_process() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate(destinationName = "First"))
        emitter.emit(candidate(destinationName = "Second"))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_IS_INITIAL_KEY)).isTrue()
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_IS_INITIAL_KEY)).isFalse()
    }

    @Test
    fun emits_stack_depths_when_the_navigator_reports_them() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate(stackDepthBefore = 2, stackDepthAfter = 3))

        val attributes = exporter.finishedSpanItems.single().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isEqualTo(2L)
        assertThat(attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isEqualTo(3L)
    }

    /** Activity transitions have no depth to report, so the attributes must be absent, not zero. */
    @Test
    fun omits_stack_depths_when_the_navigator_has_none() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate())

        val attributes = exporter.finishedSpanItems.single().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isNull()
        assertThat(attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isNull()
    }

    @Test
    fun upgrades_unknown_trigger_to_user_tap_inside_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)

        emitter.emit(candidate(), navigationTrigger = "unknown")

        val attributes = exporter.finishedSpanItems.last().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("user_tap")
    }

    @Test
    fun upgrades_absent_trigger_to_user_tap_inside_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)

        emitter.emit(candidate())

        val attributes = exporter.finishedSpanItems.last().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("user_tap")
    }

    /**
     * A system back press is the more specific fact, so it survives even though a tap was live.
     * This is the only trigger the emitter never upgrades.
     */
    @Test
    fun keeps_back_press_trigger_inside_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)

        emitter.emit(candidate(), navigationTrigger = "back_press")

        val attributes = exporter.finishedSpanItems.last().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }

    /**
     * A pop with no recorded back press is reported `programmatic` by the collectors. Inside a click
     * window that is a tap-driven pop — a toolbar "up" or a "close" button — which is the commonest
     * tap-driven back navigation and would otherwise read as code-driven.
     */
    @Test
    fun upgrades_programmatic_pop_to_user_tap_inside_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)

        emitter.emit(candidate(), navigationTrigger = "programmatic")

        val attributes = exporter.finishedSpanItems.last().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("user_tap")
    }

    /** Without a live tap, a programmatic pop stays programmatic. */
    @Test
    fun keeps_programmatic_trigger_without_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate(), navigationTrigger = "programmatic")

        val attributes = exporter.finishedSpanItems.single().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun does_not_report_user_tap_without_a_click_interaction() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate(), navigationTrigger = "unknown")

        val attributes = exporter.finishedSpanItems.single().attributes
        assertThat(attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    private fun tracerFor(exporter: InMemorySpanExporter): Tracer {
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        return OpenTelemetrySdk
            .builder()
            .setTracerProvider(tracerProvider)
            .build()
            .getTracer("test-navigation-common")
    }

    /** Opens a live interaction window, the way `ClickEventGenerator` does on a tap. */
    private fun beginClickInteraction(tracer: Tracer) {
        val clickSpan = tracer.spanBuilder("ui.interaction").startSpan()
        ActiveInteractionContext.begin(clickSpan)
        clickSpan.end()
    }

    private fun candidate(
        destinationName: String = "Details",
        stackDepthBefore: Int? = null,
        stackDepthAfter: Int? = null,
    ) = NavigationTransitionCandidate(
        source = NavigationNode(type = NavigationNodeType.ACTIVITY, name = "Home"),
        destination = NavigationNode(type = NavigationNodeType.FRAGMENT, name = destinationName),
        transitionType = NavigationTransitionType.PUSH,
        entryType = NavigationEntryType.INTERNAL,
        timestampNanos = 1234L,
        stackDepthBefore = stackDepthBefore,
        stackDepthAfter = stackDepthAfter,
    )
}
