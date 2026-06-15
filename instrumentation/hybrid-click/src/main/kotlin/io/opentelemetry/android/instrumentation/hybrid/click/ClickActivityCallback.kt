/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.app.Activity
import androidx.fragment.app.FragmentActivity
import io.opentelemetry.android.internal.services.visiblescreen.activities.DefaultingActivityLifecycleCallbacks
import java.util.Collections
import java.util.WeakHashMap

internal class ClickActivityCallback(
    private val clickEventGenerator: ClickEventGenerator,
) : DefaultingActivityLifecycleCallbacks {
    // Lazy so the androidx.fragment classes it references are only loaded once we have confirmed
    // (inside the guarded registerDialogTracking) that the app actually uses fragments. Creating it
    // eagerly here would resolve FragmentManager at construction time and crash with
    // NoClassDefFoundError on apps that exclude the compileOnly androidx.fragment dependency.
    private val dialogCallback by lazy { DialogFragmentClickCallback(clickEventGenerator) }

    /** Activities whose FragmentManager already has the dialog callback registered. */
    private val dialogTrackedActivities: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        clickEventGenerator.startTracking(activity.window)
        registerDialogTracking(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        super.onActivityPaused(activity)
        clickEventGenerator.stopTracking(activity.window)
    }

    /**
     * Hooks the Activity's [androidx.fragment.app.FragmentManager] so dialog windows get tracked.
     *
     * Registered once per Activity (recursively, to also cover child FragmentManagers). Guarded so
     * apps without androidx.fragment on the classpath simply skip dialog tracking instead of
     * crashing.
     */
    private fun registerDialogTracking(activity: Activity) {
        try {
            if (activity !is FragmentActivity) return
            if (!dialogTrackedActivities.add(activity)) return
            activity.supportFragmentManager
                .registerFragmentLifecycleCallbacks(dialogCallback, true)
        } catch (_: Throwable) {
            // androidx.fragment may be absent; dialog tracking is best-effort.
        }
    }
}
