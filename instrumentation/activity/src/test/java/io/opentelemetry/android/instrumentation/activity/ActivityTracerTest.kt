/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.mockk.slot
import io.mockk.just
import io.mockk.Runs
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer
import io.opentelemetry.android.instrumentation.common.ActiveSpan
import io.opentelemetry.android.internal.services.visiblescreen.VisibleScreenTracker
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@ExtendWith(MockKExtension::class)
class ActivityTracerTest {
    private companion object {
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private lateinit var tracer: Tracer

    @RelaxedMockK
    private lateinit var visibleScreenTracker: VisibleScreenTracker
    private val appStartupTimer = AppStartupTimer()
    private lateinit var activeSpan: ActiveSpan

    @BeforeEach
    fun setup() {
        tracer = otelTesting.openTelemetry.getTracer("testTracer")
        activeSpan = ActiveSpan(visibleScreenTracker::previouslyVisibleScreen)
        every { visibleScreenTracker.previouslyVisibleScreen } returns null
    }

    @Test
    fun stampsCanonicalUiHostAttributes() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "FirstActivity",
            )
        trackableTracer.initiateRestartSpanIfNecessary(false)
        trackableTracer.endActiveSpan()

        val attributes = this.singleSpan.attributes
        assertEquals("activity", attributes.get(RumConstants.UI_HOST_KIND_KEY))
        assertEquals(
            attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
            attributes.get(RumConstants.UI_HOST_NAME_KEY),
        )
        assertEquals("restarted", attributes.get(RumConstants.UI_HOST_LIFECYCLE_EVENT_KEY))
        // The superseded key keeps its PascalCase value on the same span.
        assertEquals("Restarted", attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
    }

    @Test
    fun restart_nonInitialActivity() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "FirstActivity",
            )
        trackableTracer.initiateRestartSpanIfNecessary(false)
        trackableTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, span.name)
        assertEquals("Restarted", span.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertNull(span.attributes.get(RumConstants.START_TYPE_KEY))
    }

    @Test
    fun restart_initialActivity() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.initiateRestartSpanIfNecessary(false)
        trackableTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("hot", span.attributes.get(RumConstants.START_TYPE_KEY))
    }

    @Test
    fun restart_initialActivity_multiActivityApp() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.initiateRestartSpanIfNecessary(true)
        trackableTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, span.name)
        assertEquals("Restarted", span.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertNull(span.attributes.get(RumConstants.START_TYPE_KEY))
    }

    @Test
    fun create_nonInitialActivity() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "FirstActivity",
            )

        trackableTracer.startActivityCreation()
        trackableTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, span.name)
        assertEquals("Created", span.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertNull(span.attributes.get(RumConstants.START_TYPE_KEY))
    }

    @Test
    fun create_initialActivity() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.startActivityCreation()
        trackableTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
    }

    /**
     * A warm start re-runs onCreate, so the hierarchy is inflated and drawn from scratch and its
     * TTID is the same quantity cold start measures. The span therefore stays open past
     * onActivityPostResumed until the frame is on screen.
     */
    @Test
    fun warmStart_waitsForFirstDraw_thenRecordsTtidAndEnds() {
        val activity = mockk<Activity>()
        val decorView = mockk<android.view.View>(relaxed = true)
        val observer = mockk<android.view.ViewTreeObserver>(relaxed = true)
        val window = mockk<android.view.Window>(relaxed = true)
        every { window.decorView } returns decorView
        every { activity.window } returns window
        every { decorView.viewTreeObserver } returns observer
        every { observer.isAlive } returns true
        val drawListener = slot<android.view.ViewTreeObserver.OnDrawListener>()
        every { observer.addOnDrawListener(capture(drawListener)) } just Runs
        val posted = slot<Runnable>()
        every { decorView.post(capture(posted)) } returns true

        val trackableTracer =
            ActivityTracer(
                activity = activity,
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(activity)

        // Resumed has returned, but the frame has not been drawn: nothing is exported yet.
        assertTrue(otelTesting.spans.isEmpty())

        drawListener.captured.onDraw()
        posted.captured.run()

        val span = this.singleSpan
        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.any { it.name == "app.start.phase.initial_display" })
    }

    /**
     * Backgrounding before the first frame legitimately ends the span early and without a TTID --
     * no frame was ever shown. The late draw callback must not then end whatever span started
     * since, which is what the identity check in deferEndUntilFirstDraw guards.
     */
    @Test
    fun warmStart_drawAfterSpanAlreadyEnded_doesNotEndAnUnrelatedSpan() {
        val activity = mockk<Activity>()
        val decorView = mockk<android.view.View>(relaxed = true)
        val observer = mockk<android.view.ViewTreeObserver>(relaxed = true)
        val window = mockk<android.view.Window>(relaxed = true)
        every { window.decorView } returns decorView
        every { activity.window } returns window
        every { decorView.viewTreeObserver } returns observer
        every { observer.isAlive } returns true
        val drawListener = slot<android.view.ViewTreeObserver.OnDrawListener>()
        every { observer.addOnDrawListener(capture(drawListener)) } just Runs
        val posted = slot<Runnable>()
        every { decorView.post(capture(posted)) } returns true

        val trackableTracer =
            ActivityTracer(
                activity = activity,
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(activity)

        // onPause ends it without a frame, then an unrelated lifecycle span starts.
        trackableTracer.endActiveSpan()
        trackableTracer.startSpanIfNoneInProgress("Paused")

        drawListener.captured.onDraw()
        posted.captured.run()

        val appStart = otelTesting.spans.single { it.name == RumConstants.APP_START_SPAN_NAME }
        assertTrue(appStart.events.none { it.name == "app.start.phase.initial_display" })
        // The unrelated span is still open -- the stale callback did not close it.
        assertTrue(activeSpan.spanInProgress())
    }

    /**
     * Deferring the end must not extend how long the span stays current on the main thread.
     * `ActiveSpan.startSpan` calls `makeCurrent()`, so anything started from onResume while the
     * scope was open -- a profile fetch, a database open, a coroutine -- would be parented to
     * app.start purely for having been spawned during the wait for a frame.
     */
    @Test
    fun warmStart_popsTheSpanOffTheThreadBeforeWaitingForTheFrame() {
        val activity = mockk<Activity>()
        val decorView = mockk<android.view.View>(relaxed = true)
        val observer = mockk<android.view.ViewTreeObserver>(relaxed = true)
        val window = mockk<android.view.Window>(relaxed = true)
        every { window.decorView } returns decorView
        every { activity.window } returns window
        every { decorView.viewTreeObserver } returns observer
        every { observer.isAlive } returns true
        val drawListener = slot<android.view.ViewTreeObserver.OnDrawListener>()
        every { observer.addOnDrawListener(capture(drawListener)) } just Runs
        val posted = slot<Runnable>()
        every { decorView.post(capture(posted)) } returns true

        val trackableTracer =
            ActivityTracer(
                activity = activity,
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        val beforeStart = Span.current().spanContext.spanId
        trackableTracer.startActivityCreation()
        val whileCreating = Span.current().spanContext.spanId
        trackableTracer.endSpanForActivityResumed(activity)
        val afterResumed = Span.current().spanContext.spanId

        // The span becomes current while the activity is being created, and must be popped back
        // off once resumed returns -- even though the span itself stays open awaiting the frame.
        // Compared against the context in place beforehand rather than asserting invalidity, so
        // ambient context from another test cannot mask a real leak.
        assertNotEquals(beforeStart, whileCreating)
        assertEquals(beforeStart, afterResumed)
        assertTrue(activeSpan.spanInProgress())
        assertTrue(otelTesting.spans.isEmpty())

        drawListener.captured.onDraw()
        posted.captured.run()

        val span = this.singleSpan
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.any { it.name == "app.start.phase.initial_display" })
    }

    /** No window to observe: end immediately rather than wait for a callback that cannot arrive. */
    @Test
    fun warmStart_withoutAWindow_endsImmediately() {
        val activity = mockk<Activity>()
        every { activity.window } returns null

        val trackableTracer =
            ActivityTracer(
                activity = activity,
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                initialAppActivity = "Activity",
            )
        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(activity)

        val span = this.singleSpan
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.none { it.name == "app.start.phase.initial_display" })
    }

    @Test
    fun create_initialActivity_firstTime() {
        appStartupTimer.start(tracer, Clock.getDefault())
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
            )
        trackableTracer.startActivityCreation()
        trackableTracer.endActiveSpan()
        appStartupTimer.end()

        val spans = otelTesting.spans
        assertEquals(2, spans.size)

        val appStartSpan = spans[0]
        val innerSpan = spans[1]
        assertEquals(RumConstants.APP_START_SPAN_NAME, appStartSpan.name)
        assertEquals("cold", appStartSpan.attributes.get(RumConstants.START_TYPE_KEY))
        val launchActivityName = innerSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY)
        assertNotNull(launchActivityName)
        assertEquals(launchActivityName, appStartSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY))

        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, innerSpan.name)
        assertEquals("Created", innerSpan.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
    }

    @Test
    fun coldStart_activityName_namesTheLaunchActivity_notTheLast() {
        // In BFSI apps a splash -> biometric -> main flow creates several activities before the
        // first frame commits, which is why AppStartupTimer guards its own postCreatedFired with
        // an AtomicBoolean. ActivityTracerCache builds one ActivityTracer per activity *class*,
        // each with its own initialAppActivity (null) and its own ActiveSpan, so every one of them
        // takes the cold branch and writes to the same startup span.
        appStartupTimer.start(tracer, Clock.getDefault())

        val launchActivity = mockk<SplashActivity>()
        val secondActivity = mockk<BiometricActivity>()

        fun tracerFor(activity: Activity) =
            ActivityTracer(
                activity = activity,
                activeSpan = ActiveSpan(noPreviousScreen),
                tracer = tracer,
                appStartupTimer = appStartupTimer,
            )

        // Both activities are created before anything ends the startup span. That is the real
        // sequence: AppStartupTimer.end() returns early while the TTID draw listener is pending,
        // so the span stays open past the second activity's creation.
        val launchTracer = tracerFor(launchActivity)
        val secondTracer = tracerFor(secondActivity)
        launchTracer.startActivityCreation()
        secondTracer.startActivityCreation()
        launchTracer.endActiveSpan()
        secondTracer.endActiveSpan()
        appStartupTimer.end()

        // Guard against a false pass: if both mocks resolved to the same simple name the
        // assertion below would hold trivially.
        assertNotEquals(launchActivity.javaClass.simpleName, secondActivity.javaClass.simpleName)

        val appStartSpan = otelTesting.spans.first { it.name == RumConstants.APP_START_SPAN_NAME }
        assertEquals(
            launchActivity.javaClass.simpleName,
            appStartSpan.attributes.get(ActivityTracer.ACTIVITY_NAME_KEY),
        )
    }

    /** No previously-visible screen. A property, not a function: detekt's
     * FunctionOnlyReturningConstant flags a function whose body is a constant. */
    private val noPreviousScreen: () -> String? = { null }

    private open class SplashActivity : Activity()

    private open class BiometricActivity : Activity()

    @Test
    fun addPreviousScreen_noPrevious() {
        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
            )

        trackableTracer.startSpanIfNoneInProgress("starting")
        trackableTracer.addPreviousScreenAttribute()
        trackableTracer.endActiveSpan()

        val span = this.singleSpan
        assertNull(span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))
    }

    @Test
    fun addPreviousScreen_currentSameAsPrevious() {
        val visibleScreenTracker = mockk<VisibleScreenTracker>(relaxed = true)
        every { visibleScreenTracker.previouslyVisibleScreen } returns "Activity"

        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
            )

        trackableTracer.startSpanIfNoneInProgress("starting")
        trackableTracer.addPreviousScreenAttribute()
        trackableTracer.endActiveSpan()

        val span = this.singleSpan
        assertNull(span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY))
    }

    @Test
    fun addPreviousScreen() {
        every { visibleScreenTracker.previouslyVisibleScreen } returns "previousScreen"

        val trackableTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
            )

        trackableTracer.startSpanIfNoneInProgress("starting")
        trackableTracer.addPreviousScreenAttribute()
        trackableTracer.endActiveSpan()

        val span = this.singleSpan
        assertEquals(
            "previousScreen",
            span.attributes.get(RumConstants.LAST_SCREEN_NAME_KEY),
        )
    }

    @Test
    fun testScreenName() {
        val activityTracer =
            ActivityTracer(
                activity = mockk<Activity>(),
                activeSpan = activeSpan,
                tracer = tracer,
                appStartupTimer = appStartupTimer,
                screenName = "squarely",
            )
        activityTracer.startActivityCreation()
        activityTracer.endActiveSpan()
        val span = this.singleSpan
        assertEquals("squarely", span.attributes.get(RumConstants.SCREEN_NAME_KEY))
    }

    private val singleSpan: SpanData
        get() {
            val generatedSpans =
                otelTesting.spans
            assertEquals(1, generatedSpans.size)
            return generatedSpans[0]
        }
}
