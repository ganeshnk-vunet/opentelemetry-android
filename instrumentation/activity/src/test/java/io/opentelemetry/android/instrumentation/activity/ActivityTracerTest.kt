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
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer
import io.opentelemetry.android.instrumentation.common.ActiveSpan
import io.opentelemetry.android.internal.services.visiblescreen.VisibleScreenTracker
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        const val TTID = "app.start.phase.initial_display"

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
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(screen.activity)

        // Resumed has returned, but the frame has not been drawn: nothing is exported yet.
        assertTrue(otelTesting.spans.isEmpty())

        screen.drawFrame()

        val span = this.singleSpan
        assertEquals(RumConstants.APP_START_SPAN_NAME, span.name)
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.any { it.name == TTID })
    }

    /**
     * Backgrounding before the first frame legitimately ends the span early and without a TTID --
     * no frame was ever shown. The late draw callback must not then end whatever span started
     * since, which is what the identity check in [ActiveSpan.endDeferred] guards.
     */
    @Test
    fun warmStart_drawAfterSpanAlreadyEnded_doesNotEndAnUnrelatedSpan() {
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(screen.activity)

        // onPause ends it without a frame, then an unrelated lifecycle span starts.
        trackableTracer.endActiveSpan()
        trackableTracer.startSpanIfNoneInProgress("Paused")

        screen.drawFrame()

        val appStart = otelTesting.spans.single { it.name == RumConstants.APP_START_SPAN_NAME }
        assertTrue(appStart.events.none { it.name == TTID })
        // The unrelated span is still open -- the stale callback did not close it.
        assertTrue(activeSpan.spanInProgress())
    }

    /**
     * Waiting for the frame must not hold the active slot: [ActiveSpan.spanInProgress] is what
     * gates `startSpanIfNoneInProgress`, so a span parked there would swallow the next lifecycle
     * span and misfile its events onto app.start. An activity that finishes itself from onResume
     * pauses inside the one-frame window, which is exactly when that happens.
     */
    @Test
    fun warmStart_pausedBeforeTheFrame_stillProducesItsOwnLifecycleSpan() {
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(screen.activity)

        // onActivityPrePaused arrives before the frame: it must get a span of its own.
        trackableTracer.startSpanIfNoneInProgress("Paused")
        trackableTracer.addEvent("activityPrePaused")
        trackableTracer.endActiveSpan()

        screen.drawFrame()

        val appStart = otelTesting.spans.single { it.name == RumConstants.APP_START_SPAN_NAME }
        assertTrue(appStart.events.none { it.name == TTID })
        assertTrue(appStart.events.none { it.name == "activityPrePaused" })
        val paused = otelTesting.spans.single { it.name == RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME }
        assertEquals("Paused", paused.attributes.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY))
        assertTrue(paused.events.any { it.name == "activityPrePaused" })
    }

    /**
     * Deferring the end must not extend how long the span stays current on the main thread.
     * `ActiveSpan.startSpan` calls `makeCurrent()`, so anything started from onResume while the
     * scope was open -- a profile fetch, a database open, a coroutine -- would be parented to
     * app.start purely for having been spawned during the wait for a frame.
     */
    @Test
    fun warmStart_popsTheSpanOffTheThreadBeforeWaitingForTheFrame() {
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        val beforeStart = Span.current().spanContext.spanId
        trackableTracer.startActivityCreation()
        val whileCreating = Span.current().spanContext.spanId
        trackableTracer.endSpanForActivityResumed(screen.activity)
        val afterResumed = Span.current().spanContext.spanId

        // The span becomes current while the activity is being created, and must be popped back
        // off once resumed returns -- even though the span itself stays open awaiting the frame.
        // Compared against the context in place beforehand rather than asserting invalidity, so
        // ambient context from another test cannot mask a real leak.
        assertNotEquals(beforeStart, whileCreating)
        assertEquals(beforeStart, afterResumed)
        // Out of the active slot as well as off the thread, so the next lifecycle span can start.
        assertFalse(activeSpan.spanInProgress())
        assertTrue(otelTesting.spans.isEmpty())

        screen.drawFrame()

        val span = this.singleSpan
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.any { it.name == TTID })
    }

    /**
     * An activity created but stopped before it ever resumes -- launched into the background, or
     * behind the keyguard -- ends its warm span through a callback that never reaches
     * endSpanForActivityResumed. The wait must not survive that and land on the hot start that
     * follows: hot re-composites a hierarchy that is already laid out, so its first draw answers a
     * different question from cold's and warm's and is deliberately excluded from TTID.
     */
    @Test
    fun warmStart_stoppedBeforeResuming_doesNotLeakTheWaitOntoAHotStart() {
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endActiveSpan()

        // Back to the foreground: onActivityPreStarted makes this a "hot" app.start.
        trackableTracer.initiateRestartSpanIfNecessary(false)
        trackableTracer.endSpanForActivityResumed(screen.activity)

        assertFalse(screen.isAwaitingDraw, "hot start must not wait for a frame")
        val hot = otelTesting.spans.last { it.name == RumConstants.APP_START_SPAN_NAME }
        assertEquals("hot", hot.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(hot.events.none { it.name == TTID })
    }

    /**
     * The same leak, on the path a multi-activity app takes: `initiateRestartSpanIfNecessary`
     * declines to call it a hot start and makes a plain lifecycle span, which must not end up
     * carrying an `app.start.*` event.
     */
    @Test
    fun warmStart_stoppedBeforeResuming_doesNotLeakTheWaitOntoALifecycleSpan() {
        val screen = DrawableActivity()
        val trackableTracer = warmTracerFor(screen.activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endActiveSpan()

        trackableTracer.initiateRestartSpanIfNecessary(true)
        trackableTracer.endSpanForActivityResumed(screen.activity)

        assertFalse(screen.isAwaitingDraw, "a lifecycle span must not wait for a frame")
        val restarted = otelTesting.spans.last()
        assertEquals(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME, restarted.name)
        assertTrue(restarted.events.none { it.name == TTID })
    }

    /** No window to observe: end immediately rather than wait for a callback that cannot arrive. */
    @Test
    fun warmStart_withoutAWindow_endsImmediately() {
        val activity = mockk<Activity>()
        every { activity.window } returns null
        val trackableTracer = warmTracerFor(activity)

        trackableTracer.startActivityCreation()
        trackableTracer.endSpanForActivityResumed(activity)

        val span = this.singleSpan
        assertEquals("warm", span.attributes.get(RumConstants.START_TYPE_KEY))
        assertTrue(span.events.none { it.name == TTID })
    }

    /** A tracer that has already seen its activity once, so a creation is a warm start. */
    private fun warmTracerFor(activity: Activity) =
        ActivityTracer(
            activity = activity,
            activeSpan = activeSpan,
            tracer = tracer,
            appStartupTimer = appStartupTimer,
            initialAppActivity = "Activity",
        )


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
