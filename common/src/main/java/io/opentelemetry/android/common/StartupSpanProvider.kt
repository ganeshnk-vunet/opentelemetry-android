/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

import io.opentelemetry.api.trace.Span

/**
 * Process-scoped holder for the current `app.start` [Span].
 *
 * [AppStartupTimer] writes [startupSpan] when the startup span is created and clears it when
 * the span ends. Any instrumentation module that needs to parent its own spans under `app.start`
 * can read this field without a direct dependency on the `activity` instrumentation module.
 *
 * Thread safety: `@Volatile` guarantees that a write from the main thread is immediately visible
 * to background threads (Glide executor, Coil coroutine dispatcher, etc.) without needing
 * explicit synchronisation. Reads are safe to call from any thread.
 */
object StartupSpanProvider {
    @Volatile
    @JvmField
    var startupSpan: Span? = null
}
