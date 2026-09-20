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
     * The span is ended only if it is still the one held when the wait began. Every other
     * lifecycle callback ends the active span, so backgrounding before the first frame legitimately
     * closes this span early -- correctly, without a TTID, because no frame was ever shown. Without
     * the identity check the late callback would then end whichever span had started since.
     */
    private fun deferEndUntilFirstDraw(activity: Activity): Boolean {
        val deferred = activeSpan.currentSpan() ?: return false
        // Pop the span off the main thread now, keeping it current for exactly as long as it was
        // before the end was deferred. Without this, anything started from onResume -- a profile
        // fetch, a database open, a coroutine -- would be parented to app.start purely because it
        // happened to be spawned while waiting for a frame.
        activeSpan.closeScopeOnly()
        return FirstDrawNotifier.onNextDraw(activity) {
            if (activeSpan.currentSpan() === deferred) {
                activeSpan.addEvent(AppStartupTimer.EVENT_TTID)
                endActiveSpan()
            }
        }
    }

    fun endActiveSpan() {
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
