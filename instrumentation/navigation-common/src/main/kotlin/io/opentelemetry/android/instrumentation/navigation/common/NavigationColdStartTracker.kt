/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the next emitted [ui.navigation][NavigationConstants.SPAN_NAME] span is the first
 * one of this process, reported as `navigation.is_initial`.
 *
 * "First navigation emitted this process" is a deliberate proxy for "the cold-start first screen".
 * It is not correlated against `RumConstants.START_TYPE_KEY`, which would require navigation-common
 * to depend on the activity/startup instrumentation; process lifetime is the same granularity
 * `AppStartupTimer` already uses to classify cold/warm/hot starts.
 *
 * Process-global by nature, so it is shared by all three collectors — a host app running both a
 * View and a Compose navigator still reports exactly one initial navigation.
 */
internal object NavigationColdStartTracker {
    private val isInitialPending = AtomicBoolean(true)

    /** Returns `true` exactly once per process, for the first caller. */
    fun consumeIsInitial(): Boolean = isInitialPending.compareAndSet(true, false)

    /**
     * Restores the pending state. Exists only so tests are not order-dependent on this
     * process-global flag; there is no production reason to call it.
     */
    fun resetForTesting() {
        isInitialPending.set(true)
    }
}
