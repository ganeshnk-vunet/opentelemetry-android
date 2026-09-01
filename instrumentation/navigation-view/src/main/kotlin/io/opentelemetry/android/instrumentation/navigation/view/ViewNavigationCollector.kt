/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.instrumentation.common.DefaultScreenNameExtractor
import io.opentelemetry.android.instrumentation.common.ScreenNameExtractor
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTrigger
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTriggerResolver
import io.opentelemetry.android.instrumentation.navigation.view.models.resolveEntryType
import io.opentelemetry.sdk.common.Clock
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks Android Activity and Fragment lifecycles to automatically emit telemetry when users
 * navigate between screens. It maps lifecycle changes to [NavigationTransitionType.PUSH],
 * [NavigationTransitionType.POP], or [NavigationTransitionType.REPLACE] events.
 *
 * Threading contract: this collector is not internally synchronized. Activity and Fragment
 * lifecycle callbacks are delivered on the main thread, and [recordBackPress] is expected to be
 * called from the same main thread (via [ViewNavigationBackPress]). Driving it from other threads
 * concurrently is unsupported.
 */
internal class ViewNavigationCollector(
    private val emitter: NavigationSpanEmitter,
    private val clock: Clock,
    private val screenNameExtractor: ScreenNameExtractor = DefaultScreenNameExtractor,
) : Application.ActivityLifecycleCallbacks {

    /** The currently tracked navigation destination, representing the actively displayed screen. */
    private var currentVisibleNode: NavigationNode? = null

    /**
     * Set when the currently paused Activity is finishing, so the next Activity resume can be
     * classified as a [NavigationTransitionType.POP].
     */
    private var finishingActivityPaused: Boolean = false

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

    /**
     * Tracks [Activity] instances that have already been resumed at least once. The first resume
     * of an Activity instance reflects how it was launched (intent extras, deep link, etc.).
     * Subsequent resumes are returns from another screen and should report
     * [NavigationEntryType.INTERNAL] instead of re-evaluating the original launch intent.
     */
    private val resumedActivities: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * Timestamp of the most recent back press recorded via [recordBackPress], used to attribute the
     * next [NavigationTransitionType.POP] to [NavigationTrigger.BACK_PRESS] when it lands within
     * the back-press TTL enforced by [NavigationTriggerResolver].
     */
    private var pendingBackPressTimestampNanos: Long? = null

    /**
     * Records that a back press just occurred so the next [NavigationTransitionType.POP] can be
     * attributed to [NavigationTrigger.BACK_PRESS]. Call from the app's back handling via
     * [ViewNavigationBackPress].
     */
    internal fun recordBackPress() {
        pendingBackPressTimestampNanos = clock.now()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (activity is FragmentActivity) {
            registerFragmentCallbacksIfNeeded(activity.supportFragmentManager)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val isPop = finishingActivityPaused
        finishingActivityPaused = false

        val isFirstResume = resumedActivities.add(activity)
        val entryType =
            if (isFirstResume) resolveEntryType(activity.intent) else NavigationEntryType.INTERNAL

        val destination =
            NavigationNode(NavigationNodeType.ACTIVITY, screenNameExtractor.extract(activity))
        emitTransitionIfNeeded(
            destination = destination,
            transitionType = if (isPop) NavigationTransitionType.POP else NavigationTransitionType.PUSH,
            entryType = entryType,
        )
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.isFinishing) {
            finishingActivityPaused = true
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities.remove(activity)
        if (activity is FragmentActivity) {
            unregisterFragmentCallbacksIfNeeded(activity.supportFragmentManager)
        }
    }

    private fun registerFragmentCallbacksIfNeeded(fragmentManager: FragmentManager) {
        if (!registeredFragmentManagers.add(fragmentManager)) {
            return
        }
        backstackCountByManager[fragmentManager] = fragmentManager.backStackEntryCount
        fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true)
    }

    private fun unregisterFragmentCallbacksIfNeeded(fragmentManager: FragmentManager) {
        if (!registeredFragmentManagers.remove(fragmentManager)) {
            return
        }
        fragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks)
        backstackCountByManager.remove(fragmentManager)
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
        resumedActivities.clear()
        currentVisibleNode = null
        finishingActivityPaused = false
        pendingBackPressTimestampNanos = null
    }

    /**
     * Evaluates the requested [destination] against the current screen state. If they differ,
     * delegates to the [emitter] to record the navigation transition and updates local state.
     */
    private fun emitTransitionIfNeeded(
        destination: NavigationNode,
        transitionType: NavigationTransitionType,
        entryType: NavigationEntryType,
        stackDepthBefore: Int? = null,
        stackDepthAfter: Int? = null,
    ) {
        val source = currentVisibleNode
        if (source != null && source == destination) {
            return
        }

        val navigationTrigger =
            NavigationTriggerResolver.resolve(
                transitionType,
                pendingBackPressTimestampNanos,
                clock.now(),
            )
        pendingBackPressTimestampNanos = null
        emitter.emit(
            NavigationTransitionCandidate(
                source = source,
                destination = destination,
                transitionType = transitionType,
                entryType = entryType,
                timestampNanos = clock.now(),
                stackDepthBefore = stackDepthBefore,
                stackDepthAfter = stackDepthAfter,
            ),
            navigationTrigger = navigationTrigger.value,
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

                val transition = inferFragmentTransition(fm)
                emitTransitionIfNeeded(
                    destination =
                        NavigationNode(NavigationNodeType.FRAGMENT, screenNameExtractor.extract(f)),
                    transitionType = transition.transitionType,
                    entryType = NavigationEntryType.INTERNAL,
                    stackDepthBefore = transition.stackDepthBefore,
                    stackDepthAfter = transition.stackDepthAfter,
                )
            }
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
    private fun inferFragmentTransition(fragmentManager: FragmentManager): FragmentTransition {
        val previousCount =
            backstackCountByManager[fragmentManager] ?: fragmentManager.backStackEntryCount
        val currentCount = fragmentManager.backStackEntryCount
        backstackCountByManager[fragmentManager] = currentCount

        val transitionType =
            when {
                currentCount < previousCount -> NavigationTransitionType.POP
                currentVisibleNode?.type == NavigationNodeType.FRAGMENT -> NavigationTransitionType.REPLACE
                else -> NavigationTransitionType.PUSH
            }
        return FragmentTransition(transitionType, previousCount, currentCount)
    }

    /**
     * A classified Fragment transition together with the back-stack depths it was derived from, so
     * the depths reach the span instead of being recomputed (which would double-read
     * [FragmentManager.getBackStackEntryCount]).
     */
    private data class FragmentTransition(
        val transitionType: NavigationTransitionType,
        val stackDepthBefore: Int,
        val stackDepthAfter: Int,
    )

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

}
