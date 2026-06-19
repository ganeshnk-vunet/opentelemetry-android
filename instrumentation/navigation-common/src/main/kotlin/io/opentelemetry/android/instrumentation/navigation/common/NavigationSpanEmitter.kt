/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TIMESTAMP_NS_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRIGGER_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.SPAN_NAME
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
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
        val spanBuilder =
            tracer
                .spanBuilder(SPAN_NAME)
                .setAttribute(NAVIGATION_DESTINATION_TYPE_KEY, candidate.destination.type.name.lowercase())
                .setAttribute(NAVIGATION_DESTINATION_NAME_KEY, candidate.destination.name)
                .setAttribute(NAVIGATION_TRANSITION_TYPE_KEY, candidate.transitionType.value)
                .setAttribute(NAVIGATION_ENTRY_TYPE_KEY, candidate.entryType.value)
                .setAttribute(NAVIGATION_TIMESTAMP_NS_KEY, candidate.timestampNanos)

        navigationTrigger?.let {
            spanBuilder.setAttribute(NAVIGATION_TRIGGER_KEY, it)
        }

        candidate.source?.let {
            spanBuilder
                .setAttribute(NAVIGATION_SOURCE_TYPE_KEY, it.type.name.lowercase())
                .setAttribute(NAVIGATION_SOURCE_NAME_KEY, it.name)
        }

        val span = spanBuilder.startSpan()
        // Set screen.name after start so it wins over default attribute appenders.
        span.setAttribute(SCREEN_NAME_KEY, candidate.destination.name)
        span.end()
        NavigationActiveContext.activate(span)
        RumDiagnostics.d {
            "navigation: span dest=${candidate.destination.name} type=${candidate.destination.type.name.lowercase()}"
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
