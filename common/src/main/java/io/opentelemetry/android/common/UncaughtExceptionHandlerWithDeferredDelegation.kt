/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

/**
 * Splits crash handling into a record step and a delegate step so
 * [io.opentelemetry.android.CrashFlushHandler] can flush telemetry between them. The delegate step
 * often invokes the runtime handler, which may terminate the process immediately.
 */
interface UncaughtExceptionHandlerWithDeferredDelegation : Thread.UncaughtExceptionHandler {
    /** Record telemetry for the failure; must not invoke [delegateToNext]. */
    fun recordUnhandledException(
        thread: Thread,
        throwable: Throwable,
    )

    /** Forward to the next handler (typically ends the process on Android). */
    fun delegateToNext(
        thread: Thread,
        throwable: Throwable,
    )
}
