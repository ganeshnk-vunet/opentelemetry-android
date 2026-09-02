/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.dsl.instrumentation

import io.opentelemetry.android.agent.dsl.OpenTelemetryDslMarker
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.instrumentation.AndroidInstrumentationLoader

/**
 * Type-safe config DSL that controls how `device.app.lifecycle` instrumentation should behave.
 */
@OpenTelemetryDslMarker
class AppLifecycleReporterConfiguration internal constructor(
    private val config: OtelRumConfig,
    @Suppress("unused") private val instrumentationLoader: AndroidInstrumentationLoader,
) : CanBeEnabledAndDisabled {
    /**
     * Suppression is keyed on the instrumentation's name directly rather than on a loader lookup.
     * Resolving the instance first would make `enabled(false)` a silent no-op whenever the
     * instrumentation is not discoverable — a stripped ServiceLoader entry, say — and telemetry the
     * caller believes they switched off would keep being emitted. Matches
     * [SystemMetricsConfiguration] and [HybridClickConfiguration].
     */
    override fun enabled(enabled: Boolean) {
        if (enabled) {
            config.allowInstrumentation(APP_LIFECYCLE_INSTRUMENTATION_NAME)
        } else {
            config.suppressInstrumentation(APP_LIFECYCLE_INSTRUMENTATION_NAME)
        }
    }

    private companion object {
        private const val APP_LIFECYCLE_INSTRUMENTATION_NAME = "applifecycle"
    }
}
