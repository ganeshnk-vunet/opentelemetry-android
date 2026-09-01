/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav3

import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.common.Constants.INSTRUMENTATION_SCOPE
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationEntryType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNode
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationNodeType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTriggerResolver
import androidx.navigation3.runtime.NavKey

internal class ComposeNav3Collector(
    openTelemetryRum: OpenTelemetryRum,
    private val nameOf: (NavKey) -> String,
) {
    private val emitter: NavigationSpanEmitter =
        NavigationSpanEmitter(openTelemetryRum.openTelemetry.getTracer(INSTRUMENTATION_SCOPE))
    private val clock = openTelemetryRum.clock
    private var previousSnapshot: List<NavKey> = emptyList()
    private var pendingBackPressTimestampNanos: Long? = null

    fun recordBackPress() {
        pendingBackPressTimestampNanos = clock.now()
    }

    fun onBackStackChanged(snapshot: List<NavKey>) {
        val sourceKey = previousSnapshot.lastOrNull()
        val destinationKey = snapshot.lastOrNull()

        if (sourceKey == null && destinationKey == null) {
            return
        }

        if (sourceKey != null && destinationKey != null && sourceKey == destinationKey) {
            previousSnapshot = snapshot
            return
        }

        val transitionType = inferTransition(previousSnapshot, snapshot)
        val navigationTrigger =
            NavigationTriggerResolver.resolve(
                transitionType,
                pendingBackPressTimestampNanos,
                clock.now(),
            )
        pendingBackPressTimestampNanos = null
        val sourceNode = sourceKey?.toNavigationNode()
        val destinationNode = destinationKey?.toNavigationNode()
        if (destinationNode == null) {
            previousSnapshot = snapshot
            return
        }

        emitter.emit(
            NavigationTransitionCandidate(
                source = sourceNode,
                destination = destinationNode,
                transitionType = transitionType,
                entryType = NavigationEntryType.INTERNAL,
                timestampNanos = clock.now(),
                stackDepthBefore = previousSnapshot.size,
                stackDepthAfter = snapshot.size,
            ),
            navigationTrigger = navigationTrigger.value,
        )

        previousSnapshot = snapshot
    }

    private fun inferTransition(
        oldStack: List<NavKey>,
        newStack: List<NavKey>,
    ): NavigationTransitionType =
        when {
            newStack.size > oldStack.size -> NavigationTransitionType.PUSH
            newStack.size < oldStack.size -> NavigationTransitionType.POP
            else -> NavigationTransitionType.REPLACE
        }

    private fun NavKey.toNavigationNode(): NavigationNode =
        NavigationNode(
            type = NavigationNodeType.COMPOSE_ROUTE,
            name = nameOf(this),
        )
}
