/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav2

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ComposeNav2CollectorTest {
    private val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    private val tracerProvider: SdkTracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
    private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    private var nowNanos: Long = 1234L
    private val testClock =
        object : Clock {
            override fun now(): Long = nowNanos

            override fun nanoTime(): Long = nowNanos
        }
    private val navController = mockk<NavController>(relaxed = true)

    /** Id of the destination beneath the current top, as the live NavController would report it. */
    private var entryBelowTopId: Int? = null

    @BeforeEach
    fun setUp() {
        exporter.reset()
        nowNanos = 1234L
        entryBelowTopId = null
    }

    @Test
    fun compose_route_push_emits_span() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY)).isEqualTo("compose_route")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isEqualTo(0L)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isEqualTo(1L)
    }

    @Test
    fun compose_route_pop_emits_span_when_returning_to_existing_destination() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
        // The shadow stack keeps the popped-to destination as the new top, so a one-level pop is
        // 2 -> 1 rather than 2 -> 0.
        assertDepths(spans[0], before = 0L, after = 1L)
        assertDepths(spans[1], before = 1L, after = 2L)
        assertDepths(spans[2], before = 2L, after = 1L)
    }

    /**
     * A pop that returns to an ancestor unwinds every entry above it in one transition, so the depth
     * delta is the number of screens dropped rather than always one.
     */
    @Test
    fun compose_route_pop_to_an_ancestor_unwinds_the_whole_stack_above_it() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)
        entryBelowTopId = 2
        collector.onDestinationChanged(navController, destination("receipt", id = 3), null)
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(4)
        assertThat(spans[3].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertDepths(spans[2], before = 2L, after = 3L)
        assertDepths(spans[3], before = 3L, after = 1L)
    }

    private fun assertDepths(
        span: io.opentelemetry.sdk.trace.data.SpanData,
        before: Long,
        after: Long,
    ) {
        assertThat(span.attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isEqualTo(before)
        assertThat(span.attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isEqualTo(after)
    }

    @Test
    fun compose_route_pop_after_recent_back_press_emits_back_press_trigger() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)

        collector.recordBackPress()
        nowNanos += 100L
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }

    @Test
    fun compose_route_stale_back_press_signal_falls_back_to_programmatic() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)

        collector.recordBackPress()
        nowNanos += 1_000_000_001L
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun compose_route_push_when_previous_top_remains_below() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    @Test
    fun compose_route_replace_emits_span_when_top_swapped() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = null
        collector.onDestinationChanged(navController, destination("settings", id = 2), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
        // A replace swaps the top entry, so the depth is unchanged.
        assertDepths(spans[1], before = 1L, after = 1L)
    }

    @Test
    fun same_destination_redispatch_does_not_emit_a_second_span() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        // Re-dispatch of the same top destination (e.g. recomposition); nothing navigational changed.
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
    }

    @Test
    fun repushing_a_destination_already_on_the_stack_is_a_push_not_a_pop() {
        val collector = createCollector()
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        entryBelowTopId = 1
        collector.onDestinationChanged(navController, destination("details/{id}", id = 2), null)
        // Navigate forward to home again (home → details → home). The live entry beneath the new
        // top is details (id 2), so this must be classified as a PUSH even though home (id 1) is
        // already on the stack — not a POP.
        entryBelowTopId = 2
        collector.onDestinationChanged(navController, destination("home", id = 1), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    @Test
    fun dialog_destination_is_filtered() {
        val collector = createCollector(destinationFilter = { it.route == "dialog" })
        collector.onDestinationChanged(navController, destination("home", id = 1), null)
        collector.onDestinationChanged(navController, destination("dialog", id = 2), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
    }

    @Test
    fun multiple_navcontrollers_are_independent() {
        val first = createCollector()
        val second = createCollector()
        first.onDestinationChanged(navController, destination("home", id = 1), null)
        second.onDestinationChanged(navController, destination("feed", id = 2), null)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY)).isEqualTo("home")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY)).isEqualTo("feed")
    }

    @Test
    fun install_then_uninstall_clears_holder() {
        val instrumentation = ComposeNav2Instrumentation()
        val rum = rum()
        instrumentation.install(mockk(relaxed = true), rum)
        assertThat(NavObserverRumHolder.current()).isNotNull()
        instrumentation.uninstall(mockk(relaxed = true), rum)
        assertThat(NavObserverRumHolder.current()).isNull()
    }

    @Test
    fun composable_is_noop_when_rum_holder_empty() {
        NavObserverRumHolder.clear()
        assertThat(NavObserverRumHolder.current()).isNull()
    }

    private fun destination(route: String, id: Int = 1): NavDestination =
        mockk {
            every { this@mockk.route } returns route
            every { this@mockk.id } returns id
        }

    private fun createCollector(
        destinationFilter: (NavDestination) -> Boolean = { false },
    ): ComposeNav2Collector =
        ComposeNav2Collector(
            openTelemetryRum = rum(),
            destinationFilter = destinationFilter,
            previousEntryIdProvider = { entryBelowTopId },
        )

    private fun rum(): OpenTelemetryRum =
        mockk {
            every { openTelemetry } returns this@ComposeNav2CollectorTest.openTelemetry
            every { clock } returns testClock
        }
}
