/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY
import io.opentelemetry.android.common.RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME
import io.opentelemetry.android.common.RumConstants.APP_START_SPAN_NAME
import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumConstants.START_TYPE_KEY
import io.opentelemetry.android.common.RumConstants.UI_HOST_KIND_ACTIVITY
import io.opentelemetry.android.common.RumConstants.UI_HOST_KIND_KEY
import io.opentelemetry.android.common.RumConstants.UI_HOST_LIFECYCLE_EVENT_KEY
import io.opentelemetry.android.common.RumConstants.UI_HOST_NAME_KEY
import io.opentelemetry.android.common.RumConstants.uiHostLifecycleEventOf
import io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer
import io.opentelemetry.android.instrumentation.common.ActiveSpan
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

internal class ActivityTracer(
    activity: Activity,
    private val activeSpan: ActiveSpan,
    private val tracer: Tracer,
    private val appStartupTimer: AppStartupTimer,
    screenName: String? = null,
    private var initialAppActivity: String? = null,
) {
    private val screenName: String = screenName ?: "unknown_screen"
    private val activityName = activity.javaClass.simpleName

    /** Set when a warm `app.start` span is created, so its end can wait for the first frame. */
    private var awaitingFirstDraw: Boolean = false

    fun startSpanIfNoneInProgress(lifecycleEvent: String): ActivityTracer {
        if (activeSpan.spanInProgress()) {
            return this
        }
        activeSpan.startSpan { createLifecycleSpan(lifecycleEvent) }
        RumDiagnostics.d { "activity: span start event=$lifecycleEvent activity=$activityName" }
        return this
    }

    fun startActivityCreation(): ActivityTracer {
        activeSpan.startSpan { this.makeCreationSpan() }
        RumDiagnostics.d { "activity: span start event=Created activity=$activityName" }
        return this
    }

    private fun makeCreationSpan(): Span {
        // If the application has never loaded an activity, or this is the initial activity getting
        // re-created,
        // we name this span specially to show that it's the application starting up. Otherwise, use
        // the activity class name as the base of the span name.
        val isColdStart = initialAppActivity == null
        if (isColdStart) {
            // Surface the launch activity on the app.start span itself, not only on the child
            // activity.lifecycle span — catalog expects activity.name flat on app.start.
            // One-shot inside AppStartupTimer: this branch runs once per activity *class*, so a
            // direct write here would name the last activity created before the first frame.
            appStartupTimer.recordLaunchActivity(activityName)
            return createLifecycleSpanWithParent("Created", appStartupTimer.startupSpan)
        }
        if (activityName == initialAppActivity) {
            // A warm start re-runs onCreate, so the hierarchy is inflated, measured and laid out
            // from scratch -- the same work cold start measures. Its TTID is therefore comparable
            // with cold's, and worth waiting for the frame to record.
            awaitingFirstDraw = true
            return createAppStartSpan("warm")
        }
        return createLifecycleSpan("Created")
    }

    fun initiateRestartSpanIfNecessary(multiActivityApp: Boolean): ActivityTracer {
        if (activeSpan.spanInProgress()) {
            return this
        }
        activeSpan.startSpan { makeRestartSpan(multiActivityApp) }
        return this
    }

    private fun makeRestartSpan(multiActivityApp: Boolean): Span {
        // restarting the first activity is a "hot" AppStart
        // Note: in a multi-activity application, navigating back to the first activity can trigger
        // this, so it would not be ideal to call it an AppStart.
        if (!multiActivityApp && activityName == initialAppActivity) {
            return createAppStartSpan("hot")
        }
        return createLifecycleSpan("Restarted")
    }

    private fun createAppStartSpan(startType: String): Span {
        val span =
            tracer
                .spanBuilder(APP_START_SPAN_NAME)
                .setAttribute(ACTIVITY_NAME_KEY, activityName)
                .startSpan()
        span.setAttribute(START_TYPE_KEY, startType)
        span.setAttribute(SCREEN_NAME_KEY, screenName)
        return span
    }

    private fun createLifecycleSpan(lifecycleEvent: String): Span =
        createLifecycleSpanWithParent(lifecycleEvent, null)

    private fun createLifecycleSpanWithParent(
        lifecycleEvent: String,
        parentSpan: Span?,
    ): Span {
        val spanBuilder =
            tracer
                .spanBuilder(ACTIVITY_LIFECYCLE_SPAN_NAME)
                .setAttribute(ACTIVITY_NAME_KEY, activityName)
                .setAttribute(ACTIVITY_LIFECYCLE_EVENT_KEY, lifecycleEvent)
                // Canonical ui.host.* alongside the per-host attributes above, so one cross-platform
                // query can read Activity, Fragment and the iOS hosts the same way.
                .setAttribute(UI_HOST_KIND_KEY, UI_HOST_KIND_ACTIVITY)
                .setAttribute(UI_HOST_NAME_KEY, activityName)
                .setAttribute(UI_HOST_LIFECYCLE_EVENT_KEY, uiHostLifecycleEventOf(lifecycleEvent))
        if (parentSpan != null) {
            spanBuilder.setParent(parentSpan.storeInContext(Context.current()))
        }
        val span = spanBuilder.startSpan()
        // do this after the span is started, so we can override the default screen.name set by the
        // RumAttributeAppender.
        span.setAttribute(SCREEN_NAME_KEY, screenName)
        return span
    }

    fun endSpanForActivityResumed(activity: Activity? = null) {
        if (initialAppActivity == null) {
            initialAppActivity = activityName
        }
        if (awaitingFirstDraw) {
            awaitingFirstDraw = false
            if (activity != null && deferEndUntilFirstDraw(activity)) {
                return
            }
        }
        endActiveSpan()
    }

    /**
     * Holds the warm `app.start` span open until the first frame is on screen, then records
     * [AppStartupTimer.EVENT_TTID] and ends it.
     *
     * Returns `false` when no listener could be attached, so the caller ends the span immediately
     * rather than leaving it open for a callback that will never arrive.
     *
     * The span is ended only if it is still the one handed over when the wait began -- see
     * [ActiveSpan.endDeferred], which also ends it on any lifecycle end so it cannot outlive the
     * activity waiting for a frame that will never be drawn.
     */
    private fun deferEndUntilFirstDraw(activity: Activity): Boolean {
        // Hands the span over to the draw callback: off the main thread's context, so nothing
        // started from onResume is parented to app.start, and out of the active slot, so the next
        // lifecycle transition still gets its own span. ActiveSpan keeps ending it on any
        // lifecycle end, so backgrounding before the frame closes it -- without a TTID, correctly,
        // because no frame was shown.
        val deferred = activeSpan.deferEnd() ?: return false
        return FirstDrawNotifier.onNextDraw(activity) {
            activeSpan.endDeferred(deferred) { it.addEvent(AppStartupTimer.EVENT_TTID) }
        }
    }

    fun endActiveSpan() {
        // Any end clears the wait: a warm span can be closed by a lifecycle callback that never
        // reaches endSpanForActivityResumed -- an activity created but stopped before it resumes,
        // behind the keyguard or launched into the background. Left set, the flag would defer the
        // *next* span this tracer ends and stamp a TTID on a hot start or a plain lifecycle span.
        awaitingFirstDraw = false
        // If we happen to be in app startup, make sure this ends it. It's harmless if we're already
        // out of the startup phase.
        appStartupTimer.end()
        activeSpan.endActiveSpan()
        RumDiagnostics.d { "activity: span end activity=$activityName" }
    }

    fun addPreviousScreenAttribute(): ActivityTracer {
        activeSpan.addPreviousScreenAttribute(activityName)
        return this
    }

    fun addEvent(eventName: String): ActivityTracer {
        activeSpan.addEvent(eventName)
        return this
    }

    internal companion object {
        val ACTIVITY_NAME_KEY: AttributeKey<String> = AttributeKey.stringKey("activity.name")
    }
}
