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
import io.opentelemetry.android.common.StartupTimestampProvider
import java.util.ServiceLoader
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.internal.services.visiblescreen.activities.DefaultingActivityLifecycleCallbacks
import android.view.ViewTreeObserver
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AppStartupTimer(
    timestampProvider: StartupTimestampProvider? = null,
) {
    private lateinit var startupClock: AnchoredClock
    private var spanStartNanos: Long = 0

    private val startupTimestamps: StartupTimestampProvider? by lazy {
        timestampProvider ?: ServiceLoader.load(
            StartupTimestampProvider::class.java,
            StartupTimestampProvider::class.java.classLoader,
        ).firstOrNull()
    }

    @Volatile
    var startupSpan: Span? = null
        private set

    // whether activity has been created
    // accessed only from UI thread
    private var uiInitStarted = false

    // whether MAX_TIME_TO_UI_INIT has been exceeded
    // accessed only from UI thread
    private var uiInitTooLate = false

    // guards applicationPostCreated so it fires only for the first activity;
    // in BFSI apps a second activity (biometric/splash) can fire before the first frame
    private val postCreatedFired = AtomicBoolean(false)

    // true once a TTID OnDrawListener has been attached to the first activity's decorView;
    // used by end() to yield to the draw listener instead of ending the span prematurely
    @Volatile
    private var ttidListenerAttached = false

    // true once the first-frame draw has been detected and the ttid event emitted
    private val ttidFired = AtomicBoolean(false)

    fun start(
        tracer: Tracer,
        clock: Clock,
    ): Span {
        // guard against a double-start and just return what's already in flight.
        startupSpan?.let {
            return it
        }
        startupClock = AnchoredClock(clock)
        val sdkInitNanos = startupClock.now()

        // On API 24+, compute the process fork time once and reuse it for both the span
        // start timestamp and the app.process.creation event, so they carry the same value.
        // Anchored to the injected clock so start and end timestamps share the same time domain.
        // On API 23, fall back to SDK init time.
        val processStartNanos =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) processStartNanos(sdkInitNanos) else -1L
        spanStartNanos =
            if (processStartNanos > 0L) processStartNanos else sdkInitNanos

        val appStart =
            tracer
                .spanBuilder(RumConstants.APP_START_SPAN_NAME)
                .setStartTimestamp(spanStartNanos, TimeUnit.NANOSECONDS)
                .setAttribute(RumConstants.START_TYPE_KEY, "cold")
                .startSpan()
        this.startupSpan = appStart

        if (processStartNanos > 0L) {
            appStart.addEvent(
                EVENT_PROCESS_CREATION,
                Attributes.empty(),
                processStartNanos,
                TimeUnit.NANOSECONDS,
            )
        }

        // app.base_context — captured by AppAnchorContentProvider in the startup artifact.
        // Value is 0 when the startup artifact is not on the classpath.
        val baseContextMs = startupTimestamps?.attachBaseContextEpochMs ?: 0L
        if (baseContextMs > 0L) {
            appStart.addEvent(
                EVENT_BASE_CONTEXT,
                Attributes.empty(),
                baseContextMs,
                TimeUnit.MILLISECONDS,
            )
        }

        // app.init.contentprovider — captured by EarlyStartupContentProvider in the startup artifact.
        // Marks the end of ContentProvider init, just before Application.onCreate().
        // Value is 0 when the startup artifact is not on the classpath.
        val contentProviderMs = startupTimestamps?.contentProviderEpochMs ?: 0L
        if (contentProviderMs > 0L) {
            appStart.addEvent(
                EVENT_CONTENT_PROVIDER_INIT,
                Attributes.empty(),
                contentProviderMs,
                TimeUnit.MILLISECONDS,
            )
            // applicationPreCreated is a semantic alias for the same timestamp — it marks
            // the boundary where the OS hands control to Application.onCreate().
            appStart.addEvent(
                EVENT_APPLICATION_PRE_CREATED,
                Attributes.empty(),
                contentProviderMs,
                TimeUnit.MILLISECONDS,
            )
        }

        // applicationCreated — SDK init is complete; all of Application.onCreate() that
        // ran before the OTel SDK initialised has now been accounted for.
        appStart.addEvent(
            EVENT_APPLICATION_CREATED,
            Attributes.empty(),
            startupClock.now(),
            TimeUnit.NANOSECONDS,
        )

        return appStart
    }

    /**
     * Computes the process fork timestamp in the injected clock's time domain by subtracting
     * the elapsed realtime delta from [clockNowNanos]:
     *   processStartNanos = clockNowNanos − (elapsedRealtime − processStartElapsedRealtime) × 1_000_000
     *
     * Using [clockNowNanos] (from the injected [Clock]) as the reference — rather than
     * [System.currentTimeMillis] — keeps the span start and span end in the same time domain,
     * which is required for correct duration calculation with custom clocks.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun processStartNanos(clockNowNanos: Long): Long {
        val elapsedSinceStartNanos =
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) * 1_000_000L
        return clockNowNanos - elapsedSinceStartNanos
    }

    /**
     * Creates a lifecycle listener that starts the UI init when an activity is created.
     *
     * @return a new Application.ActivityLifecycleCallbacks instance
     */
    fun createLifecycleCallback(): ActivityLifecycleCallbacks =
        object : DefaultingActivityLifecycleCallbacks {
            override fun onActivityPreCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                // Emit only for the first activity. In apps with biometric/splash flows
                // a second activity can fire before the first frame, corrupting the timestamp.
                if (!postCreatedFired.compareAndSet(false, true)) return
                startupSpan?.addEvent(
                    EVENT_APPLICATION_POST_CREATED,
                    Attributes.empty(),
                    startupClock.now(),
                    TimeUnit.NANOSECONDS,
                )
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {
                startUiInit()
            }

            override fun onActivityResumed(activity: Activity) {
                // Only attach during startup and only once.
                if (ttidFired.get() || startupSpan == null) return

                // Skip system / biometric overlay activities so the TTID is captured
                // against the app's own first activity, not an Android system dialog.
                val className = activity.javaClass.name
                if (className.startsWith("android.") || className.startsWith("com.android.")) return

                val rootView = activity.window.decorView

                // Keep a reference so we can remove the listener from outside onDraw().
                // (Removing from within onDraw() itself is not safe on all API levels.)
                var listener: ViewTreeObserver.OnDrawListener? = null
                listener = ViewTreeObserver.OnDrawListener {
                    // Post to the next looper cycle — the frame is committed to SurfaceFlinger
                    // after this Runnable executes, which is exactly when TTID ends.
                    rootView.post {
                        if (!ttidFired.compareAndSet(false, true)) {
                            // Another Runnable already claimed TTID (shouldn't happen, but be safe).
                            listener?.let { rootView.viewTreeObserver.removeOnDrawListener(it) }
                            return@post
                        }

                        startupSpan?.addEvent(
                            EVENT_TTID,
                            Attributes.empty(),
                            startupClock.now(),
                            TimeUnit.NANOSECONDS,
                        )
                        endSpan()
                        listener?.let { rootView.viewTreeObserver.removeOnDrawListener(it) }
                    }
                }
                rootView.viewTreeObserver.addOnDrawListener(listener)
                ttidListenerAttached = true
            }
        }

    /** Called when Activity is created.  */
    private fun startUiInit() {
        if (uiInitStarted) {
            return
        }
        uiInitStarted = true
        if (spanStartNanos + MAX_TIME_TO_UI_INIT < startupClock.now()) {
            Log.d(RumConstants.OTEL_RUM_LOG_TAG, "Max time to UI init exceeded")
            uiInitTooLate = true
            clear()
        }
    }

    /**
     * Ends the startup span. If a TTID draw-listener has been attached but hasn't fired yet,
     * this call is a no-op — the draw listener will end the span at the first committed frame.
     * This prevents the common mistake of ending the AppStart span at [onActivityResumed],
     * which fires before the view tree is measured, laid out, and drawn.
     */
    fun end() {
        // Yield to the TTID draw listener — it will call endSpan() directly when the frame commits.
        if (ttidListenerAttached && !ttidFired.get()) return
        endSpan()
    }

    private fun endSpan() {
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
         * Milestone: [android.app.Application.attachBaseContext] completed; captured by
         * [io.opentelemetry.android.instrumentation.startup.AppAnchorContentProvider].
         * Present only when the startup artifact is on the classpath.
         */
        internal const val EVENT_BASE_CONTEXT = "app.base_context"

        /**
         * Milestone: all ContentProviders finished initializing; captured by
         * [io.opentelemetry.android.instrumentation.startup.EarlyStartupContentProvider].
         * Present only when the startup artifact is on the classpath.
         */
        internal const val EVENT_CONTENT_PROVIDER_INIT = "app.init.contentprovider"

        /**
         * Semantic alias for [EVENT_CONTENT_PROVIDER_INIT] — marks the OS hand-off point
         * where [android.app.Application.onCreate] is about to run.
         * Present only when the startup artifact is on the classpath.
         */
        internal const val EVENT_APPLICATION_PRE_CREATED = "applicationPreCreated"

        /**
         * Milestone: OTel SDK initialisation complete; all of [android.app.Application.onCreate]
         * that ran before the SDK was set up has now been accounted for.
         */
        internal const val EVENT_APPLICATION_CREATED = "applicationCreated"

        /**
         * Milestone: first [android.app.Activity.onActivityPreCreated] fired — the OS hand-off
         * from [android.app.Application.onCreate] to the first Activity. Emitted only once,
         * guarded by [AppStartupTimer.postCreatedFired].
         */
        internal const val EVENT_APPLICATION_POST_CREATED = "applicationPostCreated"

        /**
         * Milestone: first frame committed to SurfaceFlinger — the true end of cold-start
         * TTID (Time to Initial Display). Captured via [ViewTreeObserver.OnDrawListener] on
         * the first activity's [android.view.Window.decorView], deferred by one looper cycle
         * via [android.view.View.post] so the timestamp falls after the frame is committed.
         * This is the timestamp at which the span ends.
         */
        internal const val EVENT_TTID = "ttid"
    }
}
