/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.crash

import io.opentelemetry.android.common.UncaughtExceptionHandlerWithDeferredDelegation

internal class CrashReportingExceptionHandler(
    private val crashProcessor: (details: CrashDetails) -> Unit,
    private val existingHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
) : UncaughtExceptionHandlerWithDeferredDelegation {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        recordUnhandledException(thread, throwable)
        delegateToNext(thread, throwable)
    }

    override fun recordUnhandledException(
        thread: Thread,
        throwable: Throwable,
    ) {
        crashProcessor(CrashDetails(thread, throwable))
    }

    override fun delegateToNext(
        thread: Thread,
        throwable: Throwable,
    ) {
        existingHandler?.uncaughtException(thread, throwable)
    }
}
