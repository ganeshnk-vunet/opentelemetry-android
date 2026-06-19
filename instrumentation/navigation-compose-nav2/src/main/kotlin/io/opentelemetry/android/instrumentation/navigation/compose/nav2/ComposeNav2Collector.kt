/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav2

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.common.Constants.INSTRUMENTATION_SCOPE
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType

internal class ComposeNav2Collector(
    openTelemetryRum: OpenTelemetryRum,
    private val destinationFilter: (NavDestination) -> Boolean = ComposeNav2DestinationFilter::shouldIgnore,
    private val destinationNameExtractor: (NavDestination) -> String = ComposeNav2DestinationNameExtractor::extract,
    private val previousEntryIdProvider: (NavController) -> Int? = ::readPreviousEntryId,
) : NavController.OnDestinationChangedListener {
    private val emitter: NavigationSpanEmitter =
        NavigationSpanEmitter(openTelemetryRum.openTelemetry.getTracer(INSTRUMENTATION_SCOPE))
    private val clock = openTelemetryRum.clock
    private var currentVisibleNode: NavigationNode? = null

    /**
     * Our own view of the destination back stack, keyed by [NavDestination.id]. Maintained from the
     * sequence of destination-changed callbacks instead of reading the (version-specific, R8-fragile)
     * private `backQueue` field, so push/pop/replace inference works regardless of the Navigation
     * library version the host app resolves.
     */
    private val destinationIdStack: ArrayDeque<Int> = ArrayDeque()
    private var pendingBackPressTimestampNanos: Long? = null

    /**
     * Records that a back press just occurred so the next [NavigationTransitionType.POP] can be
     * attributed to [NavigationTrigger.BACK_PRESS]. Wire this through [rememberVunetOnBack].
     */
    fun recordBackPress() {
        pendingBackPressTimestampNanos = clock.now()
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?,
    ) {
        if (destinationFilter(destination)) {
            return
        }

        val destinationId = destination.id
        if (destinationIdStack.lastOrNull() == destinationId) {
            // Same destination re-dispatched (e.g. recomposition); nothing navigational changed.
            return
        }

        val transitionType = inferTransitionType(controller, destinationId)
        applyTransition(transitionType, destinationId)

        val destinationNode =
            NavigationNode(
                type = NavigationNodeType.COMPOSE_ROUTE,
                name = destinationNameExtractor(destination),
            )
        val navigationTrigger = resolveTrigger(transitionType)

        emitter.emit(
            NavigationTransitionCandidate(
                source = currentVisibleNode,
                destination = destinationNode,
                transitionType = transitionType,
                entryType = NavigationEntryType.INTERNAL,
                timestampNanos = clock.now(),
            ),
            navigationTrigger = navigationTrigger.value,
        )

        currentVisibleNode = destinationNode
    }

    /**
     * Classifies the transition into [destinationId] using our tracked stack:
     * - the first destination is a [NavigationTransitionType.PUSH];
     * - returning to a destination already on the stack is a [NavigationTransitionType.POP];
     * - a new destination is a [NavigationTransitionType.PUSH] when the previous top is still
     *   beneath it (live [NavController.previousBackStackEntry]), otherwise a
     *   [NavigationTransitionType.REPLACE].
     */
    private fun inferTransitionType(
        controller: NavController,
        destinationId: Int,
    ): NavigationTransitionType {
        if (destinationIdStack.isEmpty()) {
            return NavigationTransitionType.PUSH
        }
        if (destinationIdStack.contains(destinationId)) {
            return NavigationTransitionType.POP
        }
        val liveEntryBelowTopId = previousEntryIdProvider(controller)
        return if (liveEntryBelowTopId != null && liveEntryBelowTopId == destinationIdStack.last()) {
            NavigationTransitionType.PUSH
        } else {
            NavigationTransitionType.REPLACE
        }
    }

    private fun applyTransition(
        transitionType: NavigationTransitionType,
        destinationId: Int,
    ) {
        when (transitionType) {
            NavigationTransitionType.PUSH -> destinationIdStack.addLast(destinationId)
            NavigationTransitionType.POP -> {
                while (destinationIdStack.isNotEmpty() && destinationIdStack.last() != destinationId) {
                    destinationIdStack.removeLast()
                }
            }

            NavigationTransitionType.REPLACE -> {
                if (destinationIdStack.isNotEmpty()) {
                    destinationIdStack.removeLast()
                }
                destinationIdStack.addLast(destinationId)
            }
        }
    }

    /**
     * Attributes a transition to a [NavigationTrigger]. Only a [NavigationTransitionType.POP]
     * that follows a recent [recordBackPress] is reported as [NavigationTrigger.BACK_PRESS];
     * other pops are [NavigationTrigger.PROGRAMMATIC] and forward transitions are
     * [NavigationTrigger.UNKNOWN].
     */
    private fun resolveTrigger(transitionType: NavigationTransitionType): NavigationTrigger =
        when (transitionType) {
            NavigationTransitionType.POP -> {
                if (consumeBackPressSignal()) {
                    NavigationTrigger.BACK_PRESS
                } else {
                    NavigationTrigger.PROGRAMMATIC
                }
            }

            NavigationTransitionType.PUSH,
            NavigationTransitionType.REPLACE,
            -> {
                pendingBackPressTimestampNanos = null
                NavigationTrigger.UNKNOWN
            }
        }

    private fun consumeBackPressSignal(): Boolean {
        val backPressTimestampNanos = pendingBackPressTimestampNanos ?: return false
        pendingBackPressTimestampNanos = null
        return clock.now() - backPressTimestampNanos <= BACK_PRESS_SIGNAL_TTL_NANOS
    }

    private enum class NavigationTrigger(
        val value: String,
    ) {
        BACK_PRESS("back_press"),
        PROGRAMMATIC("programmatic"),
        UNKNOWN("unknown"),
    }

    companion object {
        private const val BACK_PRESS_SIGNAL_TTL_NANOS: Long = 1_000_000_000L

        /**
         * Reads the id of the destination directly beneath the current top using the public,
         * version-stable [NavController.previousBackStackEntry] (which reflects the live back stack
         * at destination-changed time). Returns `null` when there is no entry below the top.
         */
        private fun readPreviousEntryId(controller: NavController): Int? =
            controller.previousBackStackEntry?.destination?.id
    }
}
