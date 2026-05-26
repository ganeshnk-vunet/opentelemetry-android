/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import io.opentelemetry.android.common.ProcessStartTimestamps
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.internal.services.visiblescreen.activities.DefaultingActivityLifecycleCallbacks
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock
import java.util.concurrent.TimeUnit

internal class AppStartupTimer {
    private lateinit var startupClock: AnchoredClock
    private var firstPossibleTimestamp: Long = 0

    @Volatile
    var startupSpan: Span? = null
        private set

    // whether activity has been created
    // accessed only from UI thread
    private var uiInitStarted = false

    // whether MAX_TIME_TO_UI_INIT has been exceeded
    // accessed only from UI thread
    private var uiInitTooLate = false

    fun start(
        tracer: Tracer,
        clock: Clock,
    ): Span {
        // guard against a double-start and just return what's already in flight.
        startupSpan?.let {
            return it
        }
        startupClock = AnchoredClock(clock)
        firstPossibleTimestamp = startupClock.now()
        // On API 24+, backdate the span to the true process fork time so that the
        // app.process.creation and app.init.contentprovider events fall inside the span.
        // On API 23, fall back to firstPossibleTimestamp (SDK init time).
        val spanStartNanos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                processStartEpochMs() * 1_000_000L
            } else {
                firstPossibleTimestamp
            }
        val appStart =
            tracer
                .spanBuilder("AppStart")
                .setStartTimestamp(spanStartNanos, TimeUnit.NANOSECONDS)
                .setAttribute(RumConstants.START_TYPE_KEY, "cold")
                .startSpan()
        this.startupSpan = appStart
        addEarlyStartupEvents(appStart)
        // firstPossibleTimestamp is captured right now — when the SDK finishes building
        // inside Application.onCreate(). This is a reliable marker for the end of
        // Application.onCreate() since SDK init is typically the last step there.
        appStart.addEvent(
            EVENT_APPLICATION_ON_CREATE_POST,
            Attributes.empty(),
            firstPossibleTimestamp / 1_000_000L,
            TimeUnit.MILLISECONDS,
        )
        return appStart
    }

    /**
     * Back-dates process-level milestones onto the AppStart span using timestamps that were
     * captured before the OTel SDK was initialised.
     *
     * app.process.creation — derived from [Process.getStartElapsedRealtime] (API 24+).
     *   Silently omitted on API 23.
     *
     * app.init.contentprovider — recorded by [EarlyStartupContentProvider] before
     *   Application.onCreate(); present only when the startup instrumentation artifact is on
     *   the classpath. Silently omitted when the value is 0 (provider did not run).
     */
    private fun addEarlyStartupEvents(appStart: Span) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appStart.addEvent(
                EVENT_PROCESS_CREATION,
                Attributes.empty(),
                processStartEpochMs(),
                TimeUnit.MILLISECONDS,
            )
        }
        val bcMs = ProcessStartTimestamps.attachBaseContextEpochMs
        if (bcMs > 0L) {
            appStart.addEvent(EVENT_BASE_CONTEXT, Attributes.empty(), bcMs, TimeUnit.MILLISECONDS)
        }
        val cpMs = ProcessStartTimestamps.contentProviderEpochMs
        if (cpMs > 0L) {
            appStart.addEvent(EVENT_CONTENT_PROVIDER_INIT, Attributes.empty(), cpMs, TimeUnit.MILLISECONDS)
            appStart.addEvent(EVENT_APPLICATION_PRE_CREATED, Attributes.empty(), cpMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Converts [Process.getStartElapsedRealtime] to a wall-clock epoch value:
     *   processStartEpochMs = nowMs − (elapsedRealtime − processStartElapsedRealtime)
     *
     * Assumes the wall clock was not adjusted between process fork and now, which is safe
     * for the typical sub-second cold-start window.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun processStartEpochMs(): Long {
        val nowMs = System.currentTimeMillis()
        return nowMs - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }

    /**
     * Creates a lifecycle listener that starts the UI init when an activity is created.
     *
     * @return a new Application.ActivityLifecycleCallbacks instance
     */
    fun createLifecycleCallback(): ActivityLifecycleCallbacks =
        object : DefaultingActivityLifecycleCallbacks {
            private var appPostCreatedEmitted = false

            override fun onActivityPreCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                // Fires immediately after Application.onCreate() returns and the OS hands
                // control to the first Activity. Emit only once — for the very first Activity.
                if (!appPostCreatedEmitted) {
                    appPostCreatedEmitted = true
                    startupSpan?.addEvent(
                        EVENT_APPLICATION_POST_CREATED,
                        Attributes.empty(),
                        System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS,
                    )
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                startUiInit()
            }
        }

    /** Called when Activity is created.  */
    private fun startUiInit() {
        if (uiInitStarted) {
            return
        }
        uiInitStarted = true
        if (firstPossibleTimestamp + MAX_TIME_TO_UI_INIT < startupClock.now()) {
            Log.d(RumConstants.OTEL_RUM_LOG_TAG, "Max time to UI init exceeded")
            uiInitTooLate = true
            clear()
        }
    }

    fun end() {
        val overallAppStartSpan = this.startupSpan
        if (overallAppStartSpan != null && !uiInitTooLate) {
            overallAppStartSpan.end(startupClock.now(), TimeUnit.NANOSECONDS)
        }
        clear()
    }

    private fun clear() {
        this.startupSpan = null
    }

    companion object {
        // Maximum time from app start to creation of the UI. If this time is exceeded we will not
        // create the app start span. Long app startup could indicate that the app was really started in
        // background, in which case the measured startup time is misleading.
        private val MAX_TIME_TO_UI_INIT = TimeUnit.MINUTES.toNanos(1)

        /** Milestone: Linux process was forked. Backdated via [Process.getStartElapsedRealtime]. */
        internal const val EVENT_PROCESS_CREATION = "app.process.creation"

        /**
         * Milestone: Application.attachBaseContext() completed; captured by
         * [AppAnchorContentProvider] which fires before any third-party library provider.
         */
        internal const val EVENT_BASE_CONTEXT = "app.base_context"

        /**
         * Milestone: ContentProviders finished initialising (last moment before
         * Application.onCreate). Present only when the startup instrumentation artifact is
         * on the classpath.
         */
        internal const val EVENT_CONTENT_PROVIDER_INIT = "app.init.contentprovider"

        /**
         * Milestone: Application.onCreate() is about to start. Fires at the same instant as
         * [EVENT_CONTENT_PROVIDER_INIT] but mirrors the Activity lifecycle naming pattern.
         */
        internal const val EVENT_APPLICATION_PRE_CREATED = "applicationPreCreated"

        /**
         * Milestone: OTel SDK built inside Application.onCreate() = the app's core
         * initialisation is done. Mirrors activityCreated.
         */
        internal const val EVENT_APPLICATION_ON_CREATE_POST = "applicationCreated"

        /**
         * Milestone: Application.onCreate() returned = OS is handing control to the first
         * Activity. Captured on the first onActivityPreCreated callback.
         * Mirrors activityPostCreated.
         */
        internal const val EVENT_APPLICATION_POST_CREATED = "applicationPostCreated"
    }
}
