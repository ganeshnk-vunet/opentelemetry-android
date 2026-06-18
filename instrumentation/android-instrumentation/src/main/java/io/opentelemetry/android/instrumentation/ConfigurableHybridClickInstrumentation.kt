/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation

/**
 * Optional capability for instrumentation implementations that support hybrid click configuration.
 */
interface ConfigurableHybridClickInstrumentation {
    /**
     * Sets the active-context window duration (in milliseconds) used after a click event.
     * While open, the click span is the main-thread current context.
     */
    fun setActiveContextWindowMillis(value: Long)

    /**
     * Sets the fallback TTL (in milliseconds) during which a finished/finishing click can still
     * parent cross-thread work (network requests, navigation) that runs without its own context.
     * Should be >= [setActiveContextWindowMillis] to ensure network calls triggered by the click
     * can still attach.
     */
    fun setInteractionFallbackTtlMillis(value: Long)
}
