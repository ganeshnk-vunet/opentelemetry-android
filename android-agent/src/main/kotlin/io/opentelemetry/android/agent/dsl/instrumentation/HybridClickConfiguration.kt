/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.dsl.instrumentation

import io.opentelemetry.android.agent.dsl.OpenTelemetryDslMarker
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.instrumentation.AndroidInstrumentationLoader
import io.opentelemetry.android.instrumentation.ConfigurableHybridClickInstrumentation

/**
 * Type-safe config DSL that controls how hybrid click instrumentation should behave.
 */
@OpenTelemetryDslMarker
class HybridClickConfiguration internal constructor(
    private val config: OtelRumConfig,
    instrumentationLoader: AndroidInstrumentationLoader,
) : CanBeEnabledAndDisabled {
    private val hybridClickInstrumentation: ConfigurableHybridClickInstrumentation? by lazy {
        instrumentationLoader
            .getAll()
            .firstOrNull { it.name == HYBRID_CLICK_INSTRUMENTATION_NAME }
            ?.let { it as? ConfigurableHybridClickInstrumentation }
    }

    /**
     * Sets how long (in milliseconds) the click span context remains active after a click.
     */
    fun activeContextWindowMillis(value: Long) {
        require(value > 0) { "activeContextWindowMillis must be > 0, but was $value." }
        hybridClickInstrumentation?.setActiveContextWindowMillis(value)
    }

    /**
     * Sets how long (in milliseconds) a click can still parent cross-thread work (network requests,
     * navigation) that runs without its own context.
     */
    fun interactionFallbackTtlMillis(value: Long) {
        require(value > 0) { "interactionFallbackTtlMillis must be > 0, but was $value." }
        hybridClickInstrumentation?.setInteractionFallbackTtlMillis(value)
    }

    override fun enabled(enabled: Boolean) {
        if (enabled) {
            config.allowInstrumentation(HYBRID_CLICK_INSTRUMENTATION_NAME)
        } else {
            config.suppressInstrumentation(HYBRID_CLICK_INSTRUMENTATION_NAME)
        }
    }

    private companion object {
        private const val HYBRID_CLICK_INSTRUMENTATION_NAME = "hybrid.click"
    }
}
