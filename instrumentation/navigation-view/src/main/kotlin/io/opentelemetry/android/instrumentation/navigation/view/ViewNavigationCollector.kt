/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.instrumentation.common.DefaultScreenNameExtractor
import io.opentelemetry.android.instrumentation.common.ScreenNameExtractor
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationTrigger
import io.opentelemetry.android.instrumentation.navigation.view.models.NavigationTransitionType
import io.opentelemetry.android.instrumentation.navigation.view.models.resolveEntryType
import io.opentelemetry.sdk.common.Clock
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks Android Activity and Fragment lifecycles to automatically emit telemetry when users
 * navigate between screens. It maps lifecycle changes to [NavigationTransitionType.PUSH],
 * [NavigationTransitionType.POP], or [NavigationTransitionType.REPLACE] events.
 */
internal class ViewNavigationCollector(
    private val emitter: ViewNavigationSpanEmitter,
    private val clock: Clock,
    private val screenNameExtractor: ScreenNameExtractor = DefaultScreenNameExtractor,
) : Application.ActivityLifecycleCallbacks {

    private companion object {
        const val BACK_PRESS_SIGNAL_TTL_NS: Long = 1_000_000_000L
    }

    /** The currently tracked navigation destination, representing the actively displayed screen. */
    private var currentVisibleNode: NavigationNode? = null

    /**
     * Set when the currently paused Activity is finishing, so the next Activity resume can be
     * classified as a [NavigationTransitionType.POP].
     */
    private var finishingActivityPaused: Activity? = null

    /**
     * Stores the historical backstack frame count for each FragmentManager. By comparing
     * the previous count against the current count, we can deduce if fragments were added (PUSH)
     * or popped off the stack (POP) during transitions.
     */
    private val backstackCountByManager: MutableMap<FragmentManager, Int> = WeakHashMap()

    /**
     * Keeps track of which [FragmentManager] instances have already been bound, using weak
     * references so a forgotten unregister (e.g. missed [onActivityDestroyed]) cannot keep the
     * [FragmentManager] or its host Activity alive.
     */
    private val registeredFragmentManagers: MutableSet<FragmentManager> =
        Collections.newSetFromMap(WeakHashMap())

    /** Tracks the host Activity for each registered FragmentManager. */
    private val hostActivityByFragmentManager: MutableMap<FragmentManager, Activity> = WeakHashMap()

    /** Back press callbacks registered on supported AndroidX Activity hosts. */
    private val backPressedCallbacks: MutableMap<ComponentActivity, OnBackPressedCallback> = WeakHashMap()

    /** Timestamp of the most recent observed user back press for each host Activity. */
    private val pendingBackPressByActivity: MutableMap<Activity, Long> = WeakHashMap()

    /**
     * Tracks [Activity] instances that have already been resumed at least once. The first resume
     * of an Activity instance reflects how it was launched (intent extras, deep link, etc.).
     * Subsequent resumes are returns from another screen and should report
     * [NavigationEntryType.INTERNAL] instead of re-evaluating the original launch intent.
     */
    private val resumedActivities: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity is ComponentActivity) {
            registerBackPressedCallbackIfNeeded(activity)
        }
        if (activity is FragmentActivity) {
            registerFragmentCallbacksIfNeeded(activity.supportFragmentManager, activity)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val popSource = finishingActivityPaused
        finishingActivityPaused = null
        val transitionType = if (popSource != null) NavigationTransitionType.POP else NavigationTransitionType.PUSH

        val isFirstResume = resumedActivities.add(activity)
        val entryType =
            if (isFirstResume) resolveEntryType(activity.intent) else NavigationEntryType.INTERNAL

        val destination = NavigationNode(NavigationNodeType.ACTIVITY, screenNameExtractor.extract(activity))
        emitTransitionIfNeeded(
            destination = destination,
            transitionType = transitionType,
            entryType = entryType,
            trigger = resolveTrigger(transitionType, popSource),
        )
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.isFinishing) {
            finishingActivityPaused = activity
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities.remove(activity)
        if (activity is ComponentActivity) {
            unregisterBackPressedCallbackIfNeeded(activity)
        }
        if (activity is FragmentActivity) {
            unregisterFragmentCallbacksIfNeeded(activity.supportFragmentManager)
        }
    }

    private fun registerFragmentCallbacksIfNeeded(
        fragmentManager: FragmentManager,
        hostActivity: Activity,
    ) {
        if (!registeredFragmentManagers.add(fragmentManager)) {
            return
        }
        hostActivityByFragmentManager[fragmentManager] = hostActivity
        backstackCountByManager[fragmentManager] = fragmentManager.backStackEntryCount
        fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true)
    }

    private fun unregisterFragmentCallbacksIfNeeded(fragmentManager: FragmentManager) {
        if (!registeredFragmentManagers.remove(fragmentManager)) {
            return
        }
        fragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks)
        backstackCountByManager.remove(fragmentManager)
        hostActivityByFragmentManager.remove(fragmentManager)
    }

    private fun registerBackPressedCallbackIfNeeded(activity: ComponentActivity) {
        if (backPressedCallbacks.containsKey(activity)) {
            return
        }

        val callback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    pendingBackPressByActivity[activity] = clock.now()
                    isEnabled = false
                    try {
                        activity.onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            }

        backPressedCallbacks[activity] = callback
        activity.onBackPressedDispatcher.addCallback(callback)
    }

    private fun unregisterBackPressedCallbackIfNeeded(activity: ComponentActivity) {
        backPressedCallbacks.remove(activity)?.remove()
        if (finishingActivityPaused !== activity) {
            pendingBackPressByActivity.remove(activity)
        }
    }

    /**
     * Unregisters fragment lifecycle callbacks from every tracked [FragmentManager] and resets
     * per-install state. Call from [ViewNavigationInstrumentation.uninstall] so fragment listeners
     * are not left attached after the Activity lifecycle callback is removed.
     */
    internal fun cleanup() {
        registeredFragmentManagers.toList().forEach { fragmentManager ->
            unregisterFragmentCallbacksIfNeeded(fragmentManager)
        }
        backPressedCallbacks.values.toList().forEach { callback ->
            callback.remove()
        }
        backPressedCallbacks.clear()
        pendingBackPressByActivity.clear()
        resumedActivities.clear()
        currentVisibleNode = null
        finishingActivityPaused = null
    }

    /**
     * Evaluates the requested [destination] against the current screen state. If they differ,
     * delegates to the [emitter] to record the navigation transition and updates local state.
     */
    private fun emitTransitionIfNeeded(
        destination: NavigationNode,
        transitionType: NavigationTransitionType,
        entryType: NavigationEntryType,
        trigger: NavigationTrigger,
    ) {
        val source = currentVisibleNode
        if (source != null && source == destination) {
            return
        }

        emitter.emit(
            NavigationTransitionCandidate(
                source = source,
                destination = destination,
                transitionType = transitionType,
                entryType = entryType,
                trigger = trigger,
                timestampNanos = clock.now(),
            ),
        )
        currentVisibleNode = destination
    }

    private val fragmentLifecycleCallbacks =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(
                fm: FragmentManager,
                f: Fragment,
            ) {
                if (!f.isVisible || f.parentFragment != null || f is DialogFragment) {
                    return
                }

                val transitionType = inferFragmentTransitionType(fm)

                emitTransitionIfNeeded(
                    destination = NavigationNode(NavigationNodeType.FRAGMENT, screenNameExtractor.extract(f)),
                    transitionType = transitionType,
                    entryType = NavigationEntryType.INTERNAL,
                    trigger = resolveTrigger(transitionType, hostActivityByFragmentManager[fm]),
                )
            }
        }

    private fun resolveTrigger(
        transitionType: NavigationTransitionType,
        hostActivity: Activity?,
    ): NavigationTrigger {
        if (transitionType != NavigationTransitionType.POP || hostActivity !is ComponentActivity) {
            return NavigationTrigger.UNKNOWN
        }

        return if (consumeBackPressSignal(hostActivity)) {
            NavigationTrigger.BACK_PRESS
        } else {
            NavigationTrigger.PROGRAMMATIC
        }
    }

    private fun consumeBackPressSignal(activity: Activity): Boolean {
        val backPressTimestamp = pendingBackPressByActivity.remove(activity) ?: return false
        return clock.now() - backPressTimestamp <= BACK_PRESS_SIGNAL_TTL_NS
    }

    /**
     * Derives the logical Fragment transition type from back stack depth changes.
     *
     * A smaller back stack means a [NavigationTransitionType.POP]. Otherwise, if another Fragment is
     * already the current visible node, the resumed Fragment is treated as a
     * [NavigationTransitionType.REPLACE]; otherwise it is a [NavigationTransitionType.PUSH]. Fragment removal
     * callbacks are intentionally ignored because forward `replace(...)` transactions also
     * destroy the previous Fragment, which would otherwise be misclassified as a back navigation.
     */
    private fun inferFragmentTransitionType(fragmentManager: FragmentManager): NavigationTransitionType {
        val previousCount = backstackCountByManager[fragmentManager] ?: fragmentManager.backStackEntryCount
        val currentCount = fragmentManager.backStackEntryCount
        backstackCountByManager[fragmentManager] = currentCount

        if (currentCount < previousCount) {
            return NavigationTransitionType.POP
        }
        if (currentVisibleNode?.type == NavigationNodeType.FRAGMENT) {
            return NavigationTransitionType.REPLACE
        }
        return NavigationTransitionType.PUSH
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit
}
