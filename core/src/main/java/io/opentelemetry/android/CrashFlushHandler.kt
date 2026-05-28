/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.util.Log
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.UncaughtExceptionHandlerWithDeferredDelegation
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Installs a [Thread.UncaughtExceptionHandler] that force-flushes all signal
 * providers (traces, logs, metrics) after recording the crash but before delegating to
 * the runtime handler (which often terminates the process immediately).
 *
 * Handlers that implement [UncaughtExceptionHandlerWithDeferredDelegation] (crash
 * instrumentation) are split so we only call [recordUnhandledException] before flush, then
 * [delegateToNext] after.
 *
 * This ensures that telemetry emitted during a crash (including the crash event itself)
 * is persisted before the process terminates, without requiring individual instrumentations
 * to access [OpenTelemetrySdk] directly.
 */
internal class CrashFlushHandler(
    private val sdk: OpenTelemetrySdk,
    private val flushTimeout: Duration = DEFAULT_FLUSH_TIMEOUT,
) {
    companion object {
        private val DEFAULT_FLUSH_TIMEOUT = 10.seconds
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            FlushOnCrashExceptionHandler(
                sdk,
                previous,
                flushTimeout
            ),
        )
    }

    internal class FlushOnCrashExceptionHandler(
        private val sdk: OpenTelemetrySdk,
        private val previousHandler: Thread.UncaughtExceptionHandler?,
        private val flushTimeout: Duration,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            // Record-only first: the crash handler chains to the runtime handler, which often
            // kills the process (SIGKILL) before we return — so we must not call full
            // uncaughtException until after flush.
            when (val p = previousHandler) {
                is UncaughtExceptionHandlerWithDeferredDelegation ->
                    p.recordUnhandledException(thread, throwable)
                else -> previousHandler?.uncaughtException(thread, throwable)
            }
            try {
                awaitCompletion(
                    flushTimeout,
                    sdk.sdkLoggerProvider.forceFlush(),
                    sdk.sdkTracerProvider.forceFlush(),
                    sdk.sdkMeterProvider.forceFlush()
                )
            } catch (e: Exception) {
                Log.w(RumConstants.OTEL_RUM_LOG_TAG, "Failed to flush telemetry on crash", e)
            }
            when (val p = previousHandler) {
                is UncaughtExceptionHandlerWithDeferredDelegation ->
                    p.delegateToNext(thread, throwable)
                else -> {
                    // Already ran full previousHandler above (may have killed the process).
                }
            }
        }

        private fun awaitCompletion(
            atMost: Duration,
            vararg completableItems: CompletableResultCode,
        ) {
            val latch = CountDownLatch(completableItems.size)
            for (completableResult in completableItems) {
                completableResult.whenComplete(latch::countDown)
            }
            try {
                latch.await(atMost.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
