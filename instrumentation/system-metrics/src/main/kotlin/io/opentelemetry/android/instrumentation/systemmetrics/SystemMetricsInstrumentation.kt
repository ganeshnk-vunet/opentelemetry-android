/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import android.content.Context
import android.util.Log
import com.google.auto.service.AutoService
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.ConfigurableSystemMetricsInstrumentation
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

private const val COLLECTION_INTERVAL_SECONDS = 30L

/**
 * Entry point for system metrics instrumentation.
 *
 * Periodically captures a snapshot of CPU, memory, thread, and device metrics and
 * emits them as attributes on a standalone `"app.metrics"` span.
 */
@AutoService(AndroidInstrumentation::class)
class SystemMetricsInstrumentation : AndroidInstrumentation, ConfigurableSystemMetricsInstrumentation {
    override val name: String = "system-metrics"

    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var collectionIntervalSeconds: Long = COLLECTION_INTERVAL_SECONDS

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        if (scheduler != null) return
        RumDiagnostics.d { "systemMetrics: install interval=${collectionIntervalSeconds}s" }
        val newScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "otel-system-metrics").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, e ->
                    Log.e(RumConstants.OTEL_RUM_LOG_TAG, "SystemMetrics: uncaught exception on scheduler thread", e)
                }
            }
        }
        scheduler = newScheduler

        SystemMetricsSpanEmitter(
            openTelemetry = openTelemetryRum.openTelemetry,
            scheduler = newScheduler,
            intervalSeconds = collectionIntervalSeconds,
            deviceReader = DefaultDeviceMetricsReader(context),
        ).start()
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun setCollectionIntervalSeconds(value: Long) {
        collectionIntervalSeconds = value
    }
}
