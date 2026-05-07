/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.Runs
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumConstants.LAST_SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.instrumentation.common.ScreenNameExtractor
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
    private val testClock = object : Clock {
        override fun now(): Long = 1234L

        override fun nanoTime(): Long = 1234L
    }

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
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
        assertThat(spans[1].attributes.get(LAST_SCREEN_NAME_KEY)).isEqualTo("HomeActivity")
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("push")
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
        assertThat(spans[2].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
        assertThat(spans[2].attributes.get(SCREEN_NAME_KEY)).isEqualTo("DetailsFragment")
        assertThat(spans[2].attributes.get(LAST_SCREEN_NAME_KEY)).isEqualTo("HomeFragment")
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
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("unknown")
    }

    @Test
    fun emits_back_press_trigger_for_activity_pop_on_supported_host() {
        val dispatcher = OnBackPressedDispatcher()
        every { fragmentActivity.onBackPressedDispatcher } returns dispatcher
        every { fragmentActivity.intent } returns mainIntent()
        every { fragmentActivity.isFinishing } returns true

        val home = mockActivity()
        val collector = createCollector(mapOf(fragmentActivity to "DetailsActivity", home to "HomeActivity"))

        collector.onActivityCreated(fragmentActivity, null)
        collector.onActivityResumed(fragmentActivity)
        dispatcher.onBackPressed()
        collector.onActivityPaused(fragmentActivity)
        collector.onActivityResumed(home)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
    }

    @Test
    fun forwards_back_press_to_existing_dispatcher_callbacks() {
        val callbackEvents = mutableListOf<String>()
        val dispatcher = OnBackPressedDispatcher()
        dispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    callbackEvents += "app"
                }
            },
        )
        every { fragmentActivity.onBackPressedDispatcher } returns dispatcher

        val collector = createCollector(mapOf(fragmentActivity to "HostActivity"))

        collector.onActivityCreated(fragmentActivity, null)
        dispatcher.onBackPressed()

        assertThat(callbackEvents).containsExactly("app")
    }

    @Test
    fun emits_programmatic_trigger_for_activity_pop_without_back_press_on_supported_host() {
        every { fragmentActivity.intent } returns mainIntent()
        every { fragmentActivity.onBackPressedDispatcher } returns OnBackPressedDispatcher()
        every { fragmentActivity.isFinishing } returns true

        val home = mockActivity()
        val collector = createCollector(mapOf(fragmentActivity to "DetailsActivity", home to "HomeActivity"))

        collector.onActivityCreated(fragmentActivity, null)
        collector.onActivityResumed(fragmentActivity)
        collector.onActivityPaused(fragmentActivity)
        collector.onActivityResumed(home)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(2)
        assertThat(spans[1].attributes.get(ViewNavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
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
        assertThat(spans[2].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("replace")
    }

    @Test
    fun emits_pop_for_fragment_when_backstack_shrinks() {
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returnsMany listOf(0, 1, 2, 1)
        every { fragmentActivity.intent } returns mainIntent()
        every { fragmentActivity.onBackPressedDispatcher } returns OnBackPressedDispatcher()

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
        assertThat(spans[3].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[3].attributes.get(ViewNavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("programmatic")
    }

    @Test
    fun emits_back_press_trigger_for_fragment_pop_when_backstack_shrinks_after_back_press() {
        val dispatcher = OnBackPressedDispatcher()
        every { fragmentActivity.supportFragmentManager } returns fragmentManager
        every { fragmentManager.backStackEntryCount } returnsMany listOf(0, 1, 2, 1)
        every { fragmentActivity.intent } returns mainIntent()
        every { fragmentActivity.onBackPressedDispatcher } returns dispatcher

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
        dispatcher.onBackPressed()
        lifecycleSlot.captured.onFragmentResumed(fragmentManager, home)

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(4)
        assertThat(spans[3].attributes.get(ViewNavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY)).isEqualTo("pop")
        assertThat(spans[3].attributes.get(ViewNavigationConstants.NAVIGATION_TRIGGER_KEY)).isEqualTo("back_press")
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
        assertThat(spans[0].attributes.get(ViewNavigationConstants.NAVIGATION_ENTRY_TYPE_KEY)).isEqualTo("deep_link")
        assertThat(spans[2].attributes.get(ViewNavigationConstants.NAVIGATION_ENTRY_TYPE_KEY)).isEqualTo("internal")
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
        val emitter = ViewNavigationSpanEmitter(openTelemetry.getTracer("test-navigation-view"))
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
