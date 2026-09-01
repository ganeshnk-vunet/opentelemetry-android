/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav3

import androidx.navigation3.runtime.NavKey
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

class ComposeNav3CollectorTest {
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

    @BeforeEach
    fun setUp() {
        exporter.reset()
        NavObserverRumHolder.clear()
        nowNanos = 1234L
    }

    @Test
    fun push_when_backstack_grows() {
        val collector = createCollector()
        collector.onBackStackChanged(listOf(key("home")))
        collector.onBackStackChanged(listOf(key("home"), key("details")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isEqualTo(1L)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isEqualTo(2L)
    }

    @Test
    fun pop_when_backstack_shrinks() {
        val collector = createCollector()
        collector.onBackStackChanged(listOf(key("home"), key("details")))
        collector.onBackStackChanged(listOf(key("home")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY)).isEqualTo(2L)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY)).isEqualTo(1L)
    }

    @Test
    fun pop_after_recent_back_press_emits_back_press_trigger() {
        val collector = createCollector()
        collector.onBackStackChanged(listOf(key("home"), key("details")))

        collector.recordBackPress()
        nowNanos += 100L
        collector.onBackStackChanged(listOf(key("home")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }

    @Test
    fun stale_back_press_signal_falls_back_to_programmatic() {
        val collector = createCollector()
        collector.onBackStackChanged(listOf(key("home"), key("details")))

        collector.recordBackPress()
        nowNanos += 1_000_000_001L
        collector.onBackStackChanged(listOf(key("home")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun replace_when_top_swapped() {
        val collector = createCollector()
        collector.onBackStackChanged(listOf(key("home"), key("details")))
        collector.onBackStackChanged(listOf(key("home"), key("settings")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    @Test
    fun noop_when_unchanged() {
        val collector = createCollector()
        val stack = listOf(key("home"))
        collector.onBackStackChanged(stack)
        collector.onBackStackChanged(stack)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
    }

    @Test
    fun custom_name_resolver_is_used() {
        val collector = createCollector(nameOf = { "custom-name" })
        collector.onBackStackChanged(listOf(key("home")))

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY)).isEqualTo("custom-name")
    }

    @Test
    fun install_then_uninstall_clears_holder() {
        val instrumentation = ComposeNav3Instrumentation()
        val rum = rum()
        instrumentation.install(mockk(relaxed = true), rum)
        assertThat(NavObserverRumHolder.current()).isNotNull()
        instrumentation.uninstall(mockk(relaxed = true), rum)
        assertThat(NavObserverRumHolder.current()).isNull()
    }

    @Test
    fun composable_is_noop_when_rum_holder_empty() {
        assertThat(NavObserverRumHolder.current()).isNull()
    }

    private fun createCollector(nameOf: (NavKey) -> String = { (it as TestNavKey).name }): ComposeNav3Collector =
        ComposeNav3Collector(
            openTelemetryRum = rum(),
            nameOf = nameOf,
        )

    private fun key(name: String): NavKey = TestNavKey(name)

    private fun rum(): OpenTelemetryRum =
        mockk {
            every { openTelemetry } returns this@ComposeNav3CollectorTest.openTelemetry
            every { clock } returns testClock
        }

    private data class TestNavKey(
        val name: String,
    ) : NavKey
}
