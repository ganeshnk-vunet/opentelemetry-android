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

    @Test
    fun reports_duration_from_the_tap_that_opened_the_interaction_window() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.rootStartedAtNanos()!!

        emitter.emit(candidate().copy(timestampNanos = tapAtNanos + 40L * NANOS_PER_MILLI))

        assertThat(durationOfNavigation(exporter))
            .isEqualTo(40L)
    }

    @Test
    fun reports_zero_for_a_navigation_with_no_attributable_user_action() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))

        emitter.emit(candidate())

        // Zero, not absent: the key is present on every ui.navigation span so a consumer never has
        // to handle a missing column. Zero means "not measurable", and the value itself is what
        // separates these rows from measured ones -- see
        // a_slow_navigation_carries_a_real_duration_under_an_unmeasured_looking_trigger for why
        // navigation.trigger cannot do that job.
        assertThat(durationOfNavigation(exporter)).isEqualTo(0L)
    }

    @Test
    fun reports_duration_from_a_back_press_without_any_interaction_window() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))
        val backPressAtNanos = 10_000_000_000L

        emitter.emit(
            candidate().copy(
                intentAtNanos = backPressAtNanos,
                timestampNanos = backPressAtNanos + 120L * NANOS_PER_MILLI,
            ),
        )

        assertThat(durationOfNavigation(exporter))
            .isEqualTo(120L)
    }

    @Test
    fun a_back_press_takes_precedence_over_a_live_interaction_window() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.rootStartedAtNanos()!!
        val backPressAtNanos = tapAtNanos + 200L * NANOS_PER_MILLI

        emitter.emit(
            candidate().copy(
                intentAtNanos = backPressAtNanos,
                timestampNanos = backPressAtNanos + 15L * NANOS_PER_MILLI,
            ),
        )

        // A back press is the more specific fact, the same precedence resolveTrigger applies when
        // it refuses to upgrade back_press to user_tap. Timing from the tap would report 215.
        assertThat(durationOfNavigation(exporter))
            .isEqualTo(15L)
    }

    @Test
    fun reports_zero_when_the_elapsed_time_would_be_negative() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))
        val intentAtNanos = 10_000_000_000L

        emitter.emit(
            candidate().copy(
                intentAtNanos = intentAtNanos,
                timestampNanos = intentAtNanos - NANOS_PER_MILLI,
            ),
        )

        // A destination committed before the action that caused it is not a trustworthy
        // measurement, whatever produced the ordering, so it reports not-measurable.
        assertThat(durationOfNavigation(exporter)).isEqualTo(0L)
    }

    @Test
    fun duration_is_anchored_to_the_tap_and_does_not_slide_to_the_previous_navigation() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.rootStartedAtNanos()!!

        // The first navigation calls ActiveInteractionContext.activate, replacing the *active*
        // span. If the start time were read from that instead of the root context, the second
        // navigation would be timed from the first navigation rather than from the tap.
        emitter.emit(candidate(destinationName = "First").copy(timestampNanos = tapAtNanos + 30L * NANOS_PER_MILLI))
        emitter.emit(candidate(destinationName = "Second").copy(timestampNanos = tapAtNanos + 90L * NANOS_PER_MILLI))

        assertThat(navigationSpans(exporter).map { it.attributes.get(NavigationConstants.NAVIGATION_DURATION_MS_KEY) })
            .containsExactly(30L, 90L)
    }

    /**
     * `beginClickInteraction` ends its `ui.interaction` span on the same exporter, so the raw list
     * starts with the tap. Filtering by name keeps these assertions about navigation spans.
     */
    private fun navigationSpans(exporter: InMemorySpanExporter) =
        exporter.finishedSpanItems.filter { it.name == NavigationConstants.SPAN_NAME }

    private fun durationOfNavigation(exporter: InMemorySpanExporter): Long? =
        navigationSpans(exporter).single().attributes.get(NavigationConstants.NAVIGATION_DURATION_MS_KEY)

    private fun triggerOfNavigation(exporter: InMemorySpanExporter): String? =
        navigationSpans(exporter).single().attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)

    @Test
    fun reports_a_slow_navigation_after_the_interaction_parenting_window_has_expired() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        val token = beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.lastInteractionStartedAtNanos()!!

        // The 500ms parenting window closes long before this navigation commits. It exists to
        // decide parenting, where a stale parent would be wrong; it must not decide whether the
        // navigation can be timed, or the slow navigations worth investigating are exactly the
        // ones that report nothing.
        ActiveInteractionContext.end(token)
        assertThat(ActiveInteractionContext.rootContext()).isNull()

        emitter.emit(candidate().copy(timestampNanos = tapAtNanos + 3_200L * NANOS_PER_MILLI))

        assertThat(durationOfNavigation(exporter)).isEqualTo(3_200L)
    }

    /**
     * The rule the docs hand consumers: `navigation.duration_ms > 0` is the discriminator for a
     * measured navigation, and `navigation.trigger` is **not**.
     *
     * Timing deliberately outlives both naming windows, so the two slowest shapes of navigation
     * report a real duration under a trigger that reads as unmeasured. A dashboard filtering to
     * `user_tap`/`back_press` would keep an 80 ms tap and drop both of these -- precisely the
     * navigations this attribute exists to surface. Anything that re-couples trigger naming to
     * timing, or that documents the trigger as the discriminator again, fails here.
     */
    @Test
    fun a_slow_navigation_carries_a_real_duration_under_an_unmeasured_looking_trigger() {
        // A tap whose navigation commits long after the 500ms parenting window closed: nothing is
        // left for resolveTrigger to upgrade, so the collector's `unknown` stands.
        val tapExporter = InMemorySpanExporter.create()
        val tapTracer = tracerFor(tapExporter)
        val token = beginClickInteraction(tapTracer)
        val tapAtNanos = ActiveInteractionContext.lastInteractionStartedAtNanos()!!
        ActiveInteractionContext.end(token)

        NavigationSpanEmitter(tapTracer).emit(
            candidate().copy(timestampNanos = tapAtNanos + 2_035L * NANOS_PER_MILLI),
            navigationTrigger = "unknown",
        )

        assertThat(triggerOfNavigation(tapExporter)).isEqualTo("unknown")
        assertThat(durationOfNavigation(tapExporter)).isEqualTo(2_035L)

        // A back press older than the 1s trigger TTL: NavigationTriggerResolver names the pop
        // `programmatic`, and the collector forwards intentAtNanos regardless.
        val backExporter = InMemorySpanExporter.create()
        val backPressAtNanos = 10_000_000_000L

        NavigationSpanEmitter(tracerFor(backExporter)).emit(
            candidate().copy(
                transitionType = NavigationTransitionType.POP,
                intentAtNanos = backPressAtNanos,
                timestampNanos = backPressAtNanos + 2_000L * NANOS_PER_MILLI,
            ),
            navigationTrigger = "programmatic",
        )

        assertThat(triggerOfNavigation(backExporter)).isEqualTo("programmatic")
        assertThat(durationOfNavigation(backExporter)).isEqualTo(2_000L)
    }

    @Test
    fun reports_a_ten_second_navigation_rather_than_dropping_it() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)
        val token = beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.lastInteractionStartedAtNanos()!!
        ActiveInteractionContext.end(token)

        emitter.emit(candidate().copy(timestampNanos = tapAtNanos + 10_000L * NANOS_PER_MILLI))

        assertThat(durationOfNavigation(exporter)).isEqualTo(10_000L)
    }

    @Test
    fun reports_zero_beyond_the_attribution_limit_rather_than_clamping_it() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))
        val intentAtNanos = 10_000_000_000L

        emitter.emit(
            candidate().copy(
                intentAtNanos = intentAtNanos,
                timestampNanos = intentAtNanos + NavigationSpanEmitter.MAX_ATTRIBUTION_NANOS + 1,
            ),
        )

        // Not clamped: a clamped value would be indistinguishable from a real navigation of
        // exactly that length. Reported as not-measurable instead.
        assertThat(durationOfNavigation(exporter)).isEqualTo(0L)
    }

    @Test
    fun reports_a_duration_exactly_at_the_attribution_limit() {
        val exporter = InMemorySpanExporter.create()
        val emitter = NavigationSpanEmitter(tracerFor(exporter))
        val intentAtNanos = 10_000_000_000L

        emitter.emit(
            candidate().copy(
                intentAtNanos = intentAtNanos,
                timestampNanos = intentAtNanos + NavigationSpanEmitter.MAX_ATTRIBUTION_NANOS,
            ),
        )

        assertThat(durationOfNavigation(exporter))
            .isEqualTo(NavigationSpanEmitter.MAX_ATTRIBUTION_NANOS / NANOS_PER_MILLI)
    }

    @Test
    fun the_interaction_start_is_not_consumed_so_two_collectors_both_time_one_navigation() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        // A Compose host running the View collector as well emits one ui.navigation span from
        // each for the same navigation. Consuming the interaction start would give the first a
        // duration and the second none.
        val viewEmitter = NavigationSpanEmitter(tracer)
        val composeEmitter = NavigationSpanEmitter(tracer)
        val token = beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.lastInteractionStartedAtNanos()!!
        ActiveInteractionContext.end(token)

        val committedAt = tapAtNanos + 800L * NANOS_PER_MILLI
        viewEmitter.emit(candidate(destinationName = "Details").copy(timestampNanos = committedAt))
        composeEmitter.emit(candidate(destinationName = "Details").copy(timestampNanos = committedAt))

        assertThat(navigationSpans(exporter).map { it.attributes.get(NavigationConstants.NAVIGATION_DURATION_MS_KEY) })
            .containsExactly(800L, 800L)
    }

    @Test
    fun the_duration_key_is_present_on_every_navigation_span() {
        val exporter = InMemorySpanExporter.create()
        val tracer = tracerFor(exporter)
        val emitter = NavigationSpanEmitter(tracer)

        // A programmatic navigation with nothing behind it...
        emitter.emit(candidate(destinationName = "Programmatic"))
        // ...and a tap-driven one.
        val token = beginClickInteraction(tracer)
        val tapAtNanos = ActiveInteractionContext.lastInteractionStartedAtNanos()!!
        ActiveInteractionContext.end(token)
        emitter.emit(
            candidate(destinationName = "Tapped").copy(timestampNanos = tapAtNanos + 250L * NANOS_PER_MILLI),
        )

        // The key is on both, so a consumer never has to handle a missing column. The values are
        // what separate them; in production navigation.trigger says which is which, and the
        // collectors always supply one.
        assertThat(navigationSpans(exporter).map { it.attributes.get(NavigationConstants.NAVIGATION_DURATION_MS_KEY) })
            .containsExactly(0L, 250L)
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

    /**
     * Opens a live interaction window, the way `ClickEventGenerator` does on a tap. Returns the
     * token so a test can expire the parenting window without clearing the interaction start.
     */
    private fun beginClickInteraction(tracer: Tracer): Long {
        val clickSpan = tracer.spanBuilder("ui.interaction").startSpan()
        val token = ActiveInteractionContext.begin(clickSpan)
        clickSpan.end()
        return token
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

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
