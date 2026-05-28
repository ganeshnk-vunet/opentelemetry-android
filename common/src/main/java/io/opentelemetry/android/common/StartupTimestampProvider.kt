/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

/**
 * Provides wall-clock timestamps (epoch milliseconds) captured before the OTel SDK initializes.
 *
 * Implementations are discovered via [java.util.ServiceLoader] when the startup instrumentation
 * artifact is on the classpath. A value of `0L` for any property means the corresponding
 * milestone was not captured (e.g. the startup artifact is not on the classpath).
 */
interface StartupTimestampProvider {
    /**
     * Wall-clock epoch ms captured immediately after [android.app.Application.attachBaseContext]
     * completes. Set by AppAnchorContentProvider (initOrder = [Int.MAX_VALUE]).
     */
    val attachBaseContextEpochMs: Long

    /**
     * Wall-clock epoch ms captured after all ContentProviders have finished initializing,
     * just before [android.app.Application.onCreate] is called.
     * Set by EarlyStartupContentProvider (initOrder = [Int.MIN_VALUE]).
     */
    val contentProviderEpochMs: Long
}
