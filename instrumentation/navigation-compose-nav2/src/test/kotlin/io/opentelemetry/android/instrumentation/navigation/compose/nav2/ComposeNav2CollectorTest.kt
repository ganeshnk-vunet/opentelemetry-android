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
