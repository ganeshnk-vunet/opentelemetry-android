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
     * Upgrades an unattributed trigger to [NavigationTrigger.USER_TAP] when the navigation happened
     * inside a live click-interaction window.
     *
     * The collectors cannot make this call themselves — they have no view of the interaction
     * context. They report `back_press`/`programmatic` for pops and `unknown` for forward
     * transitions, and only that `unknown` is replaced here.
     *
     * A non-null [hasLiveInteraction] means exactly "a tap opened a window that has not expired":
     * `ClickEventGenerator` is the only production caller of `ActiveInteractionContext.begin`, and
     * it schedules its own expiry.
     */
    private fun resolveTrigger(
        navigationTrigger: String?,
        hasLiveInteraction: Boolean,
    ): String? {
        val isUnattributed = navigationTrigger == null || navigationTrigger == NavigationTrigger.UNKNOWN.value
        return if (isUnattributed && hasLiveInteraction) {
            NavigationTrigger.USER_TAP.value
        } else {
            navigationTrigger
        }
    }

    companion object {
        /** Clears the active navigation context; call from navigation instrumentation [uninstall]. */
        @JvmStatic
        fun clearActiveContext() {
            NavigationActiveContext.clear()
        }
    }
}
