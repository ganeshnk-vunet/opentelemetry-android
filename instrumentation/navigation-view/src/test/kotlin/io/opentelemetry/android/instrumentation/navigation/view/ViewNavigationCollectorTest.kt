/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.instrumentation.common.ScreenNameExtractor
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ViewNavigationCollectorTest {
    @MockK
    private lateinit var fragmentActivity: FragmentActivity

    @MockK
    private lateinit var fragmentManager: FragmentManager

    @MockK
    private lateinit var activity: Activity

    @MockK
    private lateinit var application: Application

    private val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    private val tracerProvider: SdkTracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
    private val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    private var nowNanos: Long = 1234L
    private val testClock = object : Clock {
        override fun now(): Long = nowNanos

        override fun nanoTime(): Long = nowNanos
    }

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        nowNanos = 1234L
    }

    @Test
    fun emits_activity_to_activity_transition_with_previous_screen() {
        val first = mockk<Activity>(relaxed = true)
        val second = mockk<Activity>(relaxed = true)
        every { first.intent } returns mainIntent()
        every { second.intent } returns mainIntent()

        val collector = createCollector(nameMap = mapOf(first to "HomeActivity", second to "DetailsActivity"))

        collector.onActivityResumed(first)
        collector.onActivityResumed(second)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(SCREEN_NAME_KEY)).isEqualTo("DetailsActivity")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    @Test
    fun emits_fragment_replace_transition() {
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returns 0

        val firstFragment = mockFragment("HomeFragment")
        val secondFragment = mockFragment("DetailsFragment")
        val hostActivity = fragmentActivity
        every { hostActivity.intent } returns mainIntent()

        val collector =
            createCollector(
                nameMap =
                    mapOf(
                        hostActivity to "HostActivity",
                        firstFragment to "HomeFragment",
                        secondFragment to "DetailsFragment",
                    ),
            )

        collector.onActivityCreated(hostActivity, null)
        collector.onActivityResumed(hostActivity)
        val lifecycleSlot = slot<FragmentManager.FragmentLifecycleCallbacks>()
        verify { fragmentManager.registerFragmentLifecycleCallbacks(capture(lifecycleSlot), true) }

        lifecycleSlot.captured.onFragmentResumed(fragmentManager, firstFragment)
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, secondFragment)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
        assertThat(spans[2].attributes.get(SCREEN_NAME_KEY)).isEqualTo("DetailsFragment")
    }

    @Test
    fun instrumentation_install_is_idempotent() {
        val instrumentation = ViewNavigationInstrumentation()
        val openTelemetryRum = mockk<OpenTelemetryRum> {
            every { openTelemetry } returns this@ViewNavigationCollectorTest.openTelemetry
            every { clock } returns testClock
        }

        instrumentation.install(application, openTelemetryRum)
        instrumentation.install(application, openTelemetryRum)
        instrumentation.uninstall(application, openTelemetryRum)

        verify(exactly = 1) { application.registerActivityLifecycleCallbacks(any()) }
        verify(exactly = 1) { application.unregisterActivityLifecycleCallbacks(any()) }
    }

    @Test
    fun emits_pop_when_activity_finishes() {
        val first = mockActivity()
        val second = mockActivity()
        every { first.isFinishing } returns true

        val collector = createCollector(mapOf(first to "HomeActivity", second to "DetailsActivity"))

        collector.onActivityResumed(first)
        collector.onActivityPaused(first)
        collector.onActivityResumed(second)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun emits_back_press_trigger_when_activity_pop_follows_record() {
        val first = mockActivity()
        val second = mockActivity()
        every { first.isFinishing } returns true

        val collector = createCollector(mapOf(first to "HomeActivity", second to "DetailsActivity"))

        collector.onActivityResumed(first)
        collector.recordBackPress()
        nowNanos += 100L
        collector.onActivityPaused(first)
        collector.onActivityResumed(second)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }

    @Test
    fun stale_back_press_signal_falls_back_to_programmatic_for_activity_pop() {
        val first = mockActivity()
        val second = mockActivity()
        every { first.isFinishing } returns true

        val collector = createCollector(mapOf(first to "HomeActivity", second to "DetailsActivity"))

        collector.onActivityResumed(first)
        collector.recordBackPress()
        nowNanos += 1_000_000_001L
        collector.onActivityPaused(first)
        collector.onActivityResumed(second)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun does_not_misclassify_replace_with_addToBackStack() {
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returnsMany listOf(0, 0, 1)
        every { fragmentActivity.intent } returns mainIntent()

        val first = mockFragment("HomeFragment")
        val second = mockFragment("DetailsFragment")

        val collector =
            createCollector(
                mapOf(
                    fragmentActivity to "HostActivity",
                    first to "HomeFragment",
                    second to "DetailsFragment",
                ),
            )

        collector.onActivityCreated(fragmentActivity, null)
        collector.onActivityResumed(fragmentActivity)

        val lifecycleSlot = slot<FragmentManager.FragmentLifecycleCallbacks>()
        verify { fragmentManager.registerFragmentLifecycleCallbacks(capture(lifecycleSlot), true) }
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, first)

        every { first.isRemoving } returns true
        every { fragmentManager.isStateSaved } returns false
        lifecycleSlot.captured.onFragmentDestroyed(fragmentManager, first)

        lifecycleSlot.captured.onFragmentResumed(fragmentManager, second)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
    }

    @Test
    fun emits_pop_for_fragment_when_backstack_shrinks() {
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returnsMany listOf(0, 1, 2, 1)
        every { fragmentActivity.intent } returns mainIntent()

        val home = mockFragment("HomeFragment")
        val details = mockFragment("DetailsFragment")

        val collector =
            createCollector(
                mapOf(
                    fragmentActivity to "HostActivity",
                    home to "HomeFragment",
                    details to "DetailsFragment",
                ),
            )
        collector.onActivityCreated(fragmentActivity, null)
        collector.onActivityResumed(fragmentActivity)

        val lifecycleSlot = slot<FragmentManager.FragmentLifecycleCallbacks>()
        verify { fragmentManager.registerFragmentLifecycleCallbacks(capture(lifecycleSlot), true) }

        lifecycleSlot.captured.onFragmentResumed(fragmentManager, home)
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, details)
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, home)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(4)
        assertThat(spans[3].attributes.get(NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[3].attributes.get(NavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun back_press_record_routes_through_holder_to_active_collector() {
        // No active collector: record() is a safe no-op and the holder stays empty.
        ViewNavigationCollectorHolder.clear()
        ViewNavigationBackPress.record()
        assertThat(ViewNavigationCollectorHolder.current()).isNull()

        val instrumentation = ViewNavigationInstrumentation()
        val openTelemetryRum =
            mockk<OpenTelemetryRum> {
                every { openTelemetry } returns this@ViewNavigationCollectorTest.openTelemetry
                every { clock } returns testClock
            }
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { application.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just Runs
        every { application.unregisterActivityLifecycleCallbacks(any()) } just Runs

        instrumentation.install(application, openTelemetryRum)

        // Install publishes the active collector so the public hook can reach it.
        assertThat(ViewNavigationCollectorHolder.current()).isSameAs(callbackSlot.captured)

        instrumentation.uninstall(application, openTelemetryRum)
        assertThat(ViewNavigationCollectorHolder.current()).isNull()
    }

    @Test
    fun does_not_track_dialog_fragment_as_navigation() {
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returns 0
        every { fragmentActivity.intent } returns mainIntent()

        val dialog =
            mockk<DialogFragment>(relaxed = true) {
                every { isVisible } returns true
                every { parentFragment } returns null
            }

        val collector =
            createCollector(
                mapOf(fragmentActivity to "HostActivity", dialog to "MyDialog"),
            )
        collector.onActivityCreated(fragmentActivity, null)
        collector.onActivityResumed(fragmentActivity)

        val lifecycleSlot = slot<FragmentManager.FragmentLifecycleCallbacks>()
        verify { fragmentManager.registerFragmentLifecycleCallbacks(capture(lifecycleSlot), true) }
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, dialog)

        assertThat(exporter.finishedSpanItems).hasSize(1)
    }

    @Test
    fun entry_type_only_applies_on_first_resume_per_activity() {
        val deepLinkIntent: Intent =
            mockk {
                every { data } returns mockk()
                every { action } returns Intent.ACTION_VIEW
            }
        val home = mockActivity(deepLinkIntent)
        val details = mockActivity()

        val collector = createCollector(mapOf(home to "HomeActivity", details to "DetailsActivity"))

        collector.onActivityResumed(home)
        collector.onActivityPaused(home)
        collector.onActivityResumed(details)
        every { details.isFinishing } returns true
        collector.onActivityPaused(details)
        collector.onActivityResumed(home)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(3)
        assertThat(spans[0].attributes.get(NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY)).isEqualTo("deep_link")
        assertThat(spans[2].attributes.get(NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY)).isEqualTo("internal")
    }

    @Test
    fun uninstall_unregisters_fragment_callbacks() {
        val instrumentation = ViewNavigationInstrumentation()
        val openTelemetryRum =
            mockk<OpenTelemetryRum> {
                every { openTelemetry } returns this@ViewNavigationCollectorTest.openTelemetry
                every { clock } returns testClock
            }
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentActivity.intent } returns mainIntent()

        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { application.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just Runs
        every { application.unregisterActivityLifecycleCallbacks(any()) } just Runs

        instrumentation.install(application, openTelemetryRum)
        callbackSlot.captured.onActivityCreated(fragmentActivity, null)

        instrumentation.uninstall(application, openTelemetryRum)

        verify { fragmentManager.unregisterFragmentLifecycleCallbacks(any()) }
    }

    @Test
    fun recreate_on_rotation_emits_no_duplicate() {
        val before = mockActivity()
        val after = mockActivity()

        val collector =
            createCollector(
                mapOf(before to "HomeActivity", after to "HomeActivity"),
            )

        collector.onActivityResumed(before)
        collector.onActivityPaused(before)
        collector.onActivityDestroyed(before)
        collector.onActivityCreated(after, null)
        collector.onActivityResumed(after)

        assertThat(exporter.finishedSpanItems).hasSize(1)
    }

    private fun createCollector(nameMap: Map<Any, String>): ViewNavigationCollector {
        val screenNameExtractor = ScreenNameExtractor { instance -> nameMap[instance] ?: instance::class.java.simpleName }
        val emitter = NavigationSpanEmitter(openTelemetry.getTracer("test-navigation-view"))
        return ViewNavigationCollector(emitter, testClock, screenNameExtractor)
    }

    private fun mockFragment(screenName: String): Fragment {
        val fragment = mockk<Fragment>(relaxed = true)
        every { fragment.isVisible } returns true
        every { fragment.parentFragment } returns null
        every { fragment.toString() } returns screenName
        return fragment
    }

    private fun mainIntent(): Intent =
        mockk {
            every { data } returns null
            every { action } returns Intent.ACTION_MAIN
        }

    private fun mockActivity(intent: Intent = mainIntent()): Activity {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.intent } returns intent
        return activity
    }
}
