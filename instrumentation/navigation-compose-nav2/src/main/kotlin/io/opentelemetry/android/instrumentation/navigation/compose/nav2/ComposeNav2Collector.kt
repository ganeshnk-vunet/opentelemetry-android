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
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTrigger
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTriggerResolver

/**
 * Threading contract: this collector is not internally synchronized. `onDestinationChanged` is
 * delivered by the Navigation library on the main thread, and [recordBackPress] is expected to be
 * called from the same main thread (it is wired through `rememberVunetOnBack`, a Compose callback).
 * Driving it from other threads concurrently is unsupported.
 */
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
        // inferTransitionType only reads the stack, so this is still the pre-transition depth.
        val stackDepthBefore = destinationIdStack.size
        applyTransition(transitionType, destinationId)
        val stackDepthAfter = destinationIdStack.size

        val destinationNode =
            NavigationNode(
                type = NavigationNodeType.COMPOSE_ROUTE,
                name = destinationNameExtractor(destination),
            )
        val navigationTrigger =
            NavigationTriggerResolver.resolve(
                transitionType,
                pendingBackPressTimestampNanos,
                clock.now(),
            )
        pendingBackPressTimestampNanos = null

        emitter.emit(
            NavigationTransitionCandidate(
                source = currentVisibleNode,
                destination = destinationNode,
                transitionType = transitionType,
                entryType = NavigationEntryType.INTERNAL,
                timestampNanos = clock.now(),
                stackDepthBefore = stackDepthBefore,
                stackDepthAfter = stackDepthAfter,
            ),
            navigationTrigger = navigationTrigger.value,
        )

        currentVisibleNode = destinationNode
    }

    /**
     * Classifies the transition into [destinationId] using our tracked stack and the live entry
     * directly beneath the new top ([NavController.previousBackStackEntry]):
     * - the first destination is a [NavigationTransitionType.PUSH];
     * - if the previous top is now the entry beneath [destinationId], the stack grew, so it is a
     *   [NavigationTransitionType.PUSH] — this is checked first so that re-pushing a destination
     *   that already appears elsewhere on the stack (e.g. A → B → A) is not mistaken for a pop;
     * - otherwise, returning to a destination already on the stack is a
     *   [NavigationTransitionType.POP];
     * - any other new destination is a [NavigationTransitionType.REPLACE].
     */
    private fun inferTransitionType(
        controller: NavController,
        destinationId: Int,
    ): NavigationTransitionType {
        if (destinationIdStack.isEmpty()) {
            return NavigationTransitionType.PUSH
        }
        val liveEntryBelowTopId = previousEntryIdProvider(controller)
        if (liveEntryBelowTopId != null && liveEntryBelowTopId == destinationIdStack.last()) {
            // The previous top is still directly below the new top: the back stack grew by one.
            return NavigationTransitionType.PUSH
        }
        if (destinationIdStack.contains(destinationId)) {
            return NavigationTransitionType.POP
        }
        return NavigationTransitionType.REPLACE
    }

    /**
     * Updates [destinationIdStack] to reflect the applied [transitionType]. For a
     * [NavigationTransitionType.POP] the entries above [destinationId] are unwound while
     * [destinationId] itself is intentionally kept as the new top (it is the screen the user
     * returned to).
     */
    private fun applyTransition(
        transitionType: NavigationTransitionType,
        destinationId: Int,
    ) {
        when (transitionType) {
            NavigationTransitionType.PUSH -> destinationIdStack.addLast(destinationId)
            NavigationTransitionType.POP -> {
                // Unwind everything above destinationId, leaving it as the new top.
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

    companion object {
        /**
         * Reads the id of the destination directly beneath the current top using the public,
         * version-stable [NavController.previousBackStackEntry] (which reflects the live back stack
         * at destination-changed time). Returns `null` when there is no entry below the top.
         */
        private fun readPreviousEntryId(controller: NavController): Int? =
            controller.previousBackStackEntry?.destination?.id
    }
}
