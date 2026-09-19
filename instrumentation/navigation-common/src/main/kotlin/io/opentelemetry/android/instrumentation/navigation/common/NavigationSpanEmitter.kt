/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DURATION_MS_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_IS_INITIAL_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TIMESTAMP_NS_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRIGGER_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.SPAN_NAME
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTrigger
import io.opentelemetry.api.trace.Tracer

class NavigationSpanEmitter(
    private val tracer: Tracer,
) {
    fun emit(candidate: NavigationTransitionCandidate) {
        emit(candidate, navigationTrigger = null)
    }

    fun emit(
        candidate: NavigationTransitionCandidate,
        navigationTrigger: String?,
    ) {
        // Read before the trigger is resolved below, which needs to know whether a click
        // interaction is live. Side-effect free, so reading it earlier than the setParent use is
        // behaviorally identical.
        val interactionContext = ActiveInteractionContext.rootContext()

        val spanBuilder =
            tracer
                .spanBuilder(SPAN_NAME)
                .setAttribute(NAVIGATION_DESTINATION_TYPE_KEY, candidate.destination.type.name.lowercase())
                .setAttribute(NAVIGATION_DESTINATION_NAME_KEY, candidate.destination.name)
                .setAttribute(NAVIGATION_TRANSITION_TYPE_KEY, candidate.transitionType.value)
                .setAttribute(NAVIGATION_ENTRY_TYPE_KEY, candidate.entryType.value)
                .setAttribute(NAVIGATION_TIMESTAMP_NS_KEY, candidate.timestampNanos)
                .setAttribute(NAVIGATION_IS_INITIAL_KEY, NavigationColdStartTracker.consumeIsInitial())

        resolveTrigger(navigationTrigger, interactionContext != null)?.let {
            spanBuilder.setAttribute(NAVIGATION_TRIGGER_KEY, it)
        }

        resolveDurationMs(candidate)?.let {
            spanBuilder.setAttribute(NAVIGATION_DURATION_MS_KEY, it)
        }

        candidate.stackDepthBefore?.let {
            spanBuilder.setAttribute(NAVIGATION_STACK_DEPTH_BEFORE_KEY, it.toLong())
        }
        candidate.stackDepthAfter?.let {
            spanBuilder.setAttribute(NAVIGATION_STACK_DEPTH_AFTER_KEY, it.toLong())
        }

        candidate.source?.let {
            spanBuilder
                .setAttribute(NAVIGATION_SOURCE_TYPE_KEY, it.type.name.lowercase())
                .setAttribute(NAVIGATION_SOURCE_NAME_KEY, it.name)
        }

        interactionContext?.let { spanBuilder.setParent(it) }

        val span = spanBuilder.startSpan()
        // Set screen.name after start so it wins over default attribute appenders.
        span.setAttribute(SCREEN_NAME_KEY, candidate.destination.name)
        span.end()
        if (interactionContext != null) {
            NavigationActiveContext.activate(span)
        }
        RumDiagnostics.d {
            "navigation: span dest=${candidate.destination.name} type=${candidate.destination.type.name.lowercase()}"
        }
    }

    /**
     * Reports [NavigationTrigger.USER_TAP] when the navigation happened inside a live
     * click-interaction window and nothing more specific already explains it.
     *
     * The collectors cannot make this call themselves — they have no view of the interaction
     * context. They report `unknown` for forward transitions, and `back_press`/`programmatic` for
     * pops depending on whether a back press was recorded. Two of those three are upgraded here:
     * - `unknown` — a forward transition that happened while a tap was live.
     * - `programmatic` — only ever produced for a pop with no recorded back press, which inside a
     *   click window is a tap-driven pop: a toolbar "up" or a "close" button. The pop itself is
     *   still recorded by `navigation.transition.type`, so naming the trigger `user_tap` loses
     *   nothing and stops the commonest tap-driven back navigation reading as code-driven.
     *
     * [NavigationTrigger.BACK_PRESS] is never upgraded: a system back press is a real back press
     * even if a tap happened to be live, and it is the more specific fact of the two.
     *
     * A non-null [hasLiveInteraction] means exactly "a tap opened a window that has not expired":
     * `ClickEventGenerator` is the only production caller of `ActiveInteractionContext.begin`, and
     * it schedules its own expiry.
     *
     * Known limit: the window is not consumed here, so a genuinely programmatic navigation landing
     * inside the window of an unrelated tap is also labelled `user_tap`. Consuming it would need
     * `ActiveInteractionContext` to expose a "claim" operation, which is owned by hybrid-click and
     * shared with the HTTP instrumentations.
     */
    private fun resolveTrigger(
        navigationTrigger: String?,
        hasLiveInteraction: Boolean,
    ): String? {
        val isUpgradable =
            navigationTrigger == null ||
                navigationTrigger == NavigationTrigger.UNKNOWN.value ||
                navigationTrigger == NavigationTrigger.PROGRAMMATIC.value
        return if (isUpgradable && hasLiveInteraction) {
            NavigationTrigger.USER_TAP.value
        } else {
            navigationTrigger
        }
    }

    /**
     * Milliseconds from the user action that caused this navigation to the moment the destination
     * was committed, or `null` when no action can be attributed to it.
     *
     * Two sources, in order of specificity:
     * - [NavigationTransitionCandidate.intentAtNanos], set by a collector for a back press its
     *   trigger resolver accepted. A back press is the more specific fact and wins outright, for
     *   the same reason `resolveTrigger` never upgrades `back_press` to `user_tap`.
     * - The live interaction window, whose root span start is the tap that opened it.
     *
     * Deliberately absent, never zero, when neither applies — a genuinely programmatic navigation
     * has no user-perceived wait to report, and a zero would be indistinguishable from an instant
     * one and would drag every percentile down.
     *
     * A negative result is discarded. It would mean the destination was committed before the action
     * that caused it, which is not a duration worth reporting whatever produced it.
     *
     * **Known limit, and it biases the metric:** both sources expire — the interaction window after
     * `ClickEventGenerator.DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS` (500 ms) and a back press after
     * `NavigationTriggerResolver.BACK_PRESS_SIGNAL_TTL_NANOS` (1 s). A navigation that takes longer
     * than its window therefore reports no duration at all, so the slowest navigations are the ones
     * most likely to be missing rather than the ones recorded as slow. Read the resulting
     * distribution as "how long fast navigations took", not "how long navigations took", until the
     * attribution window is decoupled from the parenting window. Note the interaction window is
     * posted on the main looper, so a navigation delayed by a blocked main thread does not lose its
     * context the way one delayed by background work does.
     *
     * It also inherits `resolveTrigger`'s misattribution limit: a programmatic navigation landing
     * inside an unrelated tap's window is timed from that tap.
     */
    private fun resolveDurationMs(candidate: NavigationTransitionCandidate): Long? {
        val intentAtNanos = candidate.intentAtNanos ?: ActiveInteractionContext.rootStartedAtNanos()
        if (intentAtNanos == null) {
            return null
        }
        val elapsedNanos = candidate.timestampNanos - intentAtNanos
        return if (elapsedNanos < 0) null else elapsedNanos / NANOS_PER_MILLI
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Clears the active navigation context; call from navigation instrumentation [uninstall]. */
        @JvmStatic
        fun clearActiveContext() {
            NavigationActiveContext.clear()
        }
    }
}
