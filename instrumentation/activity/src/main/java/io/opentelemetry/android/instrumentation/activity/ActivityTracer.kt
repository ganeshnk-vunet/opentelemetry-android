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
            return createLifecycleSpanWithParent("Created", appStartupTimer.startupSpan)
        }
        if (activityName == initialAppActivity) {
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

    fun endSpanForActivityResumed() {
        if (initialAppActivity == null) {
            initialAppActivity = activityName
        }
        endActiveSpan()
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
