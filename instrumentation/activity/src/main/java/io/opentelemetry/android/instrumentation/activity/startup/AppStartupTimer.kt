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
import androidx.annotation.RequiresApi
import io.opentelemetry.android.common.StartupTimestampProvider
import java.util.ServiceLoader
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
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

    // whether the app-start window has been exceeded
    // accessed only from UI thread
    private var uiInitTooLate = false

    // guards app.start.phase.ui_ready so it fires only for the first activity;
    // in BFSI apps a second activity (biometric/splash) can fire before the first frame
    private val postCreatedFired = AtomicBoolean(false)

    // true once a TTID OnDrawListener has been attached to the first activity's decorView;
    // used by end() to yield to the draw listener instead of ending the span prematurely
    @Volatile
    private var ttidListenerAttached = false

    // true once the first-frame draw has been detected and the initial-display event emitted
    private val ttidFired = AtomicBoolean(false)

    // guards ContentProvider end events so they are recorded once when the timestamp becomes available
    private var contentProviderEndEventsRecorded = false

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
        // start timestamp and the app.start.phase.process event, so they carry the same value.
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

        recordAttachBaseContextEvents(appStart, sdkInitNanos)

        // Start of ContentProvider init phase — captured by AppAnchorContentProvider.
        val contentProvidersStartMs = startupTimestamps?.contentProvidersPhaseStartEpochMs ?: 0L
        if (contentProvidersStartMs > 0L) {
            appStart.addEvent(
                EVENT_CONTENT_PROVIDERS_START,
                Attributes.empty(),
                contentProvidersStartMs,
                TimeUnit.MILLISECONDS,
            )
        }

        // ContentProvider phase end — may be deferred if SDK init runs before EarlyStartupContentProvider.
        recordContentProviderEndEvents(appStart)

        // app.start.phase.application — SDK init is complete; all of Application.onCreate() that
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
     * Converts an [android.os.SystemClock.elapsedRealtime] sample into the injected [Clock]
     * time domain, using [clockNowNanos] as the reference instant (same approach as
     * [processStartNanos]).
     */
    private fun elapsedRealtimeToClockNanos(
        elapsedRealtimeMs: Long,
        clockNowNanos: Long,
    ): Long {
        val elapsedSinceMarkerNanos =
            (SystemClock.elapsedRealtime() - elapsedRealtimeMs) * 1_000_000L
        return clockNowNanos - elapsedSinceMarkerNanos
    }

    private fun recordAttachBaseContextEvents(
        appStart: Span,
        clockNowNanos: Long,
    ) {
        val startElapsed = startupTimestamps?.attachBaseContextStartElapsedRealtime ?: 0L
        val endElapsed = startupTimestamps?.attachBaseContextEndElapsedRealtime ?: 0L
        if (startElapsed <= 0L || endElapsed < startElapsed) {
            return
        }
        val startNanos = elapsedRealtimeToClockNanos(startElapsed, clockNowNanos)
        val endNanos = elapsedRealtimeToClockNanos(endElapsed, clockNowNanos)
        appStart.addEvent(
            EVENT_ATTACH_BASE_CONTEXT_START,
            Attributes.empty(),
            startNanos,
            TimeUnit.NANOSECONDS,
        )
        appStart.addEvent(
            EVENT_ATTACH_BASE_CONTEXT_END,
            Attributes.empty(),
            endNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    /**
     * Records ContentProvider phase end milestones when [StartupTimestampProvider.contentProviderEpochMs]
     * is available. Safe to call multiple times; no-ops after the first successful record or while the
     * timestamp is still zero (e.g. SDK initialized via a mid-order ContentProvider before
     * [io.opentelemetry.android.instrumentation.startup.EarlyStartupContentProvider] runs).
     */
    private fun recordContentProviderEndEvents(appStart: Span) {
        if (contentProviderEndEventsRecorded) {
            return
        }
        val contentProviderMs = startupTimestamps?.contentProviderEpochMs ?: 0L
        if (contentProviderMs <= 0L) {
            return
        }
        appStart.addEvent(
            EVENT_CONTENT_PROVIDERS_END,
            Attributes.empty(),
            contentProviderMs,
            TimeUnit.MILLISECONDS,
        )
        contentProviderEndEventsRecorded = true
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
        startupSpan?.let { recordContentProviderEndEvents(it) }
        if (spanStartNanos + RumConstants.APP_START_MAX_WINDOW_NANOS < startupClock.now()) {
            RumDiagnostics.d { "activityStartup: max time to UI init exceeded" }
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
            recordContentProviderEndEvents(overallAppStartSpan)
            overallAppStartSpan.end(startupClock.now(), TimeUnit.NANOSECONDS)
        }
        clear()
    }

    private fun clear() {
        this.startupSpan = null
        contentProviderEndEventsRecorded = false
    }

    companion object {
        /** Milestone: Linux process was forked. Backdated via [Process.getStartElapsedRealtime]. */
        internal const val EVENT_PROCESS_CREATION = "app.start.phase.process"

        /**
         * Milestone: start of [android.app.Application.attachBaseContext]. Requires startup-agent
         * weave and a declared override on the app's [android.app.Application] subclass.
         */
        internal const val EVENT_ATTACH_BASE_CONTEXT_START = "app.attach_base_context.start"

        /**
         * Milestone: end of [android.app.Application.attachBaseContext]. Requires startup-agent
         * weave and a declared override on the app's [android.app.Application] subclass.
         */
        internal const val EVENT_ATTACH_BASE_CONTEXT_END = "app.start.phase.runtime_init"

        /**
         * Milestone: start of the ContentProvider initialization phase; captured by
         * [io.opentelemetry.android.instrumentation.startup.AppAnchorContentProvider].
         */
        internal const val EVENT_CONTENT_PROVIDERS_START = "app.content_providers.start"

        /**
         * Milestone: end of the ContentProvider initialization phase; captured by
         * [io.opentelemetry.android.instrumentation.startup.EarlyStartupContentProvider].
         * Present only when the startup artifact is on the classpath.
         */
        internal const val EVENT_CONTENT_PROVIDERS_END = "app.start.phase.extensions"

        /**
         * Milestone: OTel SDK initialisation complete; all of [android.app.Application.onCreate]
         * that ran before the SDK was set up has now been accounted for.
         */
        internal const val EVENT_APPLICATION_CREATED = "app.start.phase.application"

        /**
         * Milestone: first [android.app.Activity.onActivityPreCreated] fired — the OS hand-off
         * from [android.app.Application.onCreate] to the first Activity. Emitted only once,
         * guarded by [AppStartupTimer.postCreatedFired].
         */
        internal const val EVENT_APPLICATION_POST_CREATED = "app.start.phase.ui_ready"

        /**
         * Milestone: first frame committed to SurfaceFlinger — the true end of cold-start
         * TTID (Time to Initial Display). Captured via [ViewTreeObserver.OnDrawListener] on
         * the first activity's [android.view.Window.decorView], deferred by one looper cycle
         * via [android.view.View.post] so the timestamp falls after the frame is committed.
         * This is the timestamp at which the span ends.
         */
        internal const val EVENT_TTID = "app.start.phase.initial_display"
    }
}
