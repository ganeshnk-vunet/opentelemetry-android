/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer
import io.opentelemetry.android.instrumentation.common.ScreenNameExtractor
import io.opentelemetry.android.internal.services.visiblescreen.VisibleScreenTracker
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.EventData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

internal class ActivityCallbacksTest {
    private companion object {
        const val TTID = "app.start.phase.initial_display"

        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private lateinit var tracers: ActivityTracerCache
    private lateinit var visibleScreenTracker: VisibleScreenTracker

    @BeforeEach
    fun setup() {
        val tracer = otelTesting.openTelemetry.getTracer("testTracer")
        val startupTimer = AppStartupTimer()
        visibleScreenTracker = mockk<VisibleScreenTracker>(relaxed = true)
        every { visibleScreenTracker.previouslyVisibleScreen } returns null
        val extractor = mockk<ScreenNameExtractor>(relaxed = true)
        every { extractor.extract(any<Activity>()) } returns "Activity"
        tracers = ActivityTracerCache(tracer, visibleScreenTracker, startupTimer, extractor)
    }

    @Test
    fun appStartup() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runAppStartupLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(1, spans.size)

        val creationSpan = spans[0]

        // TODO: ADD THIS TEST TO THE NEW COMPONENT(S)
        //        assertEquals("AppStart", startupSpan.getName());
        //        assertEquals("cold", startupSpan.getAttributes().get(SplunkRum.START_TYPE_KEY));
        assertEquals(
            activity.javaClass.simpleName,
            creationSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            creationSpan.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(creationSpan.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        val events = creationSpan.events
        assertEquals(9, events.size)

        checkEventExists(events, "activityPreCreated")
        checkEventExists(events, "activityCreated")
        checkEventExists(events, "activityPostCreated")

        checkEventExists(events, "activityPreStarted")
        checkEventExists(events, "activityStarted")
        checkEventExists(events, "activityPostStarted")

        checkEventExists(events, "activityPreResumed")
        checkEventExists(events, "activityResumed")
        checkEventExists(events, "activityPostResumed")
    }

    /**
     * The whole warm path, driven through the real callback sequence rather than the tracer:
     * onActivityPostResumed returns with the span still open, and the frame that follows is what
     * records the TTID and ends it.
     */
    @Test
    fun warmStart_throughTheCallbacks_recordsTtidAtTheFirstFrame() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)
        val screen = DrawableActivity()

        // First run through is the cold start, which names the initial activity.
        testHarness.runAppStartupLifecycle(screen.activity)
        otelTesting.clearSpans()

        // Second creation of the same activity is the warm start.
        testHarness.runActivityCreationLifecycle(screen.activity)
        assertTrue(otelTesting.spans.isEmpty(), "warm app.start must stay open past postResumed")

        screen.drawFrame()

        val warm = otelTesting.spans.single()
        assertEquals(RumConstants.APP_START_SPAN_NAME, warm.name)
        assertEquals("warm", warm.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(warm.events.any { it.name == TTID })
    }

    /**
     * An activity created but stopped before it ever resumes -- launched into the background, or
     * behind the keyguard -- never reaches the callback that consumes the wait. The hot start that
     * follows must not inherit it: hot re-composites a hierarchy that is already laid out, so its
     * first draw is not the quantity cold and warm report.
     */
    @Test
    fun warmStart_stoppedBeforeResuming_doesNotLeakTheWaitOntoTheHotStart() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)
        val screen = DrawableActivity()

        testHarness.runAppStartupLifecycle(screen.activity)
        otelTesting.clearSpans()

        // Warm creation that stops before resuming.
        val bundle = mockk<android.os.Bundle>()
        activityCallbacks.onActivityPreCreated(screen.activity, bundle)
        activityCallbacks.onActivityCreated(screen.activity, bundle)
        activityCallbacks.onActivityPostCreated(screen.activity, bundle)
        testHarness.runActivityStartedLifecycle(screen.activity)
        testHarness.runActivityStoppedFromPausedLifecycle(screen.activity)
        otelTesting.clearSpans()

        // Coming back to the foreground: a hot start, which must end at postResumed as before.
        testHarness.runActivityRestartedLifecycle(screen.activity)

        assertFalse(screen.isAwaitingDraw, "hot start must not wait for a frame")
        val hot = otelTesting.spans.single()
        assertEquals(RumConstants.APP_START_SPAN_NAME, hot.name)
        assertEquals("hot", hot.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(hot.events.none { it.name == TTID })
    }

    @Test
    fun activityCreation() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)
        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityCreationLifecycle(activity)
        val spans = otelTesting.spans
        assertEquals(1, spans.size)

        val span = spans[0]

        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        val events = span.events
        assertEquals(9, events.size)

        checkEventExists(events, "activityPreCreated")
        checkEventExists(events, "activityCreated")
        checkEventExists(events, "activityPostCreated")

        checkEventExists(events, "activityPreStarted")
        checkEventExists(events, "activityStarted")
        checkEventExists(events, "activityPostStarted")

        checkEventExists(events, "activityPreResumed")
        checkEventExists(events, "activityResumed")
        checkEventExists(events, "activityPostResumed")
    }

    private fun startupAppAndClearSpans(testHarness: ActivityCallbackTestHarness) {
        // make sure that the initial state has been set up & the application is started.
        testHarness.runAppStartupLifecycle(mockk<Activity>())
        otelTesting.clearSpans()
    }

    @Test
    fun activityRestart() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityRestartedLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(1, spans.size)

        val span = spans[0]

        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("hot", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        val events = span.events
        assertEquals(6, events.size)

        checkEventExists(events, "activityPreStarted")
        checkEventExists(events, "activityStarted")
        checkEventExists(events, "activityPostStarted")

        checkEventExists(events, "activityPreResumed")
        checkEventExists(events, "activityResumed")
        checkEventExists(events, "activityPostResumed")
    }

    @Test
    fun activityResumed() {
        every { visibleScreenTracker.previouslyVisibleScreen } returns "previousScreen"
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityResumedLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(1, spans.size)

        val span = spans[0]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, span.name)
        assertEquals("Resumed", span.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertEquals(
            "previousScreen",
            span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY),
        )

        val events = span.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPreResumed")
        checkEventExists(events, "activityResumed")
        checkEventExists(events, "activityPostResumed")
    }

    @Test
    fun activityDestroyedFromStopped() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityDestroyedFromStoppedLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(1, spans.size)

        val span = spans[0]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, span.name)
        assertEquals("Destroyed", span.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            span.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        val events = span.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPreDestroyed")
        checkEventExists(events, "activityDestroyed")
        checkEventExists(events, "activityPostDestroyed")
    }

    @Test
    fun activityDestroyedFromPaused() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityDestroyedFromPausedLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(2, spans.size)

        val stoppedSpan = spans[0]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, stoppedSpan.name)
        assertEquals("Stopped", stoppedSpan.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            stoppedSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            stoppedSpan.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(stoppedSpan.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        var events: List<EventData> = stoppedSpan.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPreStopped")
        checkEventExists(events, "activityStopped")
        checkEventExists(events, "activityPostStopped")

        val destroyedSpan = spans[1]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, destroyedSpan.name)
        assertEquals("Destroyed", destroyedSpan.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            destroyedSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            destroyedSpan.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(destroyedSpan.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        events = destroyedSpan.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPreDestroyed")
        checkEventExists(events, "activityDestroyed")
        checkEventExists(events, "activityPostDestroyed")
    }

    @Test
    fun activityStoppedFromRunning() {
        val activityCallbacks = ActivityCallbacks(tracers)
        val testHarness = ActivityCallbackTestHarness(activityCallbacks)

        startupAppAndClearSpans(testHarness)

        val activity = mockk<Activity>()
        // No window on a bare mock: FirstDrawNotifier falls back to ending the span
        // immediately, which is the behaviour asserted below.
        every { activity.window } returns null
        testHarness.runActivityStoppedFromRunningLifecycle(activity)

        val spans = otelTesting.spans
        assertEquals(2, spans.size)

        val stoppedSpan = spans[0]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, stoppedSpan.name)
        assertEquals("Paused", stoppedSpan.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            stoppedSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            stoppedSpan.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(stoppedSpan.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        var events: List<EventData> = stoppedSpan.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPrePaused")
        checkEventExists(events, "activityPaused")
        checkEventExists(events, "activityPostPaused")

        val destroyedSpan = spans[1]

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, destroyedSpan.name)
        assertEquals("Stopped", destroyedSpan.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertEquals(
            activity.javaClass.simpleName,
            destroyedSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
        assertEquals(
            activity.javaClass.simpleName,
            destroyedSpan.attributes.get(RumConstants.SCREEN_NAME_KEY),
        )
        assertNull(destroyedSpan.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))

        events = destroyedSpan.events
        assertEquals(3, events.size)

        checkEventExists(events, "activityPreStopped")
        checkEventExists(events, "activityStopped")
        checkEventExists(events, "activityPostStopped")
    }

    private fun checkEventExists(
        events: List<EventData>,
        eventName: String,
    ) {
        val event = events.any { e: EventData -> e.name == eventName }
        assertTrue(event, "Event with name $eventName not found")
    }
}
