/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import io.opentelemetry.android.common.RumConstants.APP_START_SPAN_NAME
import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumConstants.START_TYPE_KEY
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

    fun startSpanIfNoneInProgress(spanName: String): ActivityTracer {
        if (activeSpan.spanInProgress()) {
            return this
        }
        activeSpan.startSpan { createSpan(spanName) }
        return this
    }

    fun startActivityCreation(): ActivityTracer {
        activeSpan.startSpan { this.makeCreationSpan() }
        return this
    }

    private fun makeCreationSpan(): Span {
        // If the application has never loaded an activity, or this is the initial activity getting
        // re-created,
        // we name this span specially to show that it's the application starting up. Otherwise, use
        // the activity class name as the base of the span name.
        val isColdStart = initialAppActivity == null
        if (isColdStart) {
            return createSpanWithParent("Created", appStartupTimer.startupSpan)
        }
        if (activityName == initialAppActivity) {
            return createAppStartSpan("warm")
        }
        return createSpan("Created")
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
        return createSpan("Restarted")
    }

    private fun createAppStartSpan(startType: String): Span {
        val span = createSpan(APP_START_SPAN_NAME)
        span.setAttribute(START_TYPE_KEY, startType)
        return span
    }

    private fun createSpan(spanName: String): Span = createSpanWithParent(spanName, null)

    private fun createSpanWithParent(
        spanName: String,
        parentSpan: Span?,
    ): Span {
        val spanBuilder = tracer.spanBuilder(spanName).setAttribute(ACTIVITY_NAME_KEY, activityName)
        if (parentSpan != null) {
            spanBuilder.setParent(parentSpan.storeInContext(Context.current()))
        }
        val span = spanBuilder.startSpan()
        // do this after the span is started, so we can override the default screen.name set by the
        // RumAttributeAppender.
        span.setAttribute(SCREEN_NAME_KEY, screenName)
        return span
    }

    fun endSpanForActivityResumed() {
        if (initialAppActivity == null) {
            initialAppActivity = activityName
        }
        endActiveSpan()
    }

    /**
     * Defers ending the current span until the Activity's window draws its first frame
     * (Time To Initial Display). On first [ViewTreeObserver.OnDrawListener.onDraw] callback:
     *   1. Adds a `ttid` event to mark the exact frame-draw moment.
     *   2. Ends the span and releases the listener.
     *
     * Falls back to [endSpanForActivityResumed] immediately if:
     * - The decor view is not yet attached (ViewTreeObserver not alive), or
     * - Running below API 26 ([ViewTreeObserver.addOnDrawListener] requires API 26+
     *   to be safely removed from within the callback via
     *   [ViewTreeObserver.removeOnDrawListener]).
     */
    fun deferEndForTtid(activity: Activity) {
        if (initialAppActivity == null) {
            initialAppActivity = activityName
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            endActiveSpan()
            return
        }
        val decorView = activity.window?.decorView
        val vto = decorView?.viewTreeObserver
        if (vto == null || !vto.isAlive) {
            endActiveSpan()
            return
        }
        val spanToEnd: Span = activeSpan.currentSpan() ?: run {
            endActiveSpan()
            return
        }
        // Close the OTel scope immediately so this span is no longer the current context.
        // Without this, any subsequent spans (e.g. click events) would be parented to the
        // AppStart span while the TTID listener waits for the first draw.
        activeSpan.closeScope()
        registerTtidListener(activity, vto, spanToEnd)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerTtidListener(
        activity: Activity,
        vto: ViewTreeObserver,
        spanToEnd: Span,
    ) {
        var listener: ViewTreeObserver.OnDrawListener? = null
        listener = ViewTreeObserver.OnDrawListener {
            spanToEnd.addEvent(EVENT_TTID)
            // End span directly (scope was already closed in deferEndForTtid) and clear the
            // reference so spanInProgress() returns false for any subsequent lifecycle events.
            spanToEnd.end()
            activeSpan.clearSpan()
            appStartupTimer.end()
            // Post removal — removing an OnDrawListener from within onDraw is not safe
            // on all versions; posting to the view's handler defers it to after the frame.
            activity.window?.decorView?.post {
                activity.window?.decorView?.viewTreeObserver
                    ?.takeIf { it.isAlive }
                    ?.removeOnDrawListener(listener)
            }
        }
        vto.addOnDrawListener(listener)
    }

    fun endActiveSpan() {
        // If we happen to be in app startup, make sure this ends it. It's harmless if we're already
        // out of the startup phase.
        appStartupTimer.end()
        activeSpan.endActiveSpan()
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

        /** Milestone: first frame drawn on screen — Time To Initial Display. */
        const val EVENT_TTID = "ttid"
    }
}
