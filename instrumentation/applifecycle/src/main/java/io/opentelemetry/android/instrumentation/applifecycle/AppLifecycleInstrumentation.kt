/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import android.content.Context
import android.util.Log
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.internal.services.Services.Companion.get
import io.opentelemetry.android.internal.services.applifecycle.ApplicationStateListener

/**
 * Exports the canonical `device.app.lifecycle` span: one per app-level foreground/background
 * transition, plus a `created` span emitted once at install time (there is no process-creation
 * callback to hook, since [io.opentelemetry.android.internal.services.applifecycle.AppLifecycle]
 * only reports start/stop).
 */
@AutoService(AndroidInstrumentation::class)
class AppLifecycleInstrumentation : AndroidInstrumentation {
    private var listener: ApplicationStateListener? = null

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        // A second install would emit another `created` span and register a second listener while
        // orphaning the first — uninstall only unregisters the one it holds, so the orphan would
        // keep duplicating every transition for the life of the process.
        if (listener != null) {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "AppLifecycleInstrumentation skipping installation (already installed)",
            )
            return
        }

        val emitter =
            AppLifecycleSpanEmitter(
                openTelemetryRum.openTelemetry.tracerProvider.get("io.opentelemetry.android.applifecycle"),
            )
        // Emitted before the listener is registered, so a hot-start foreground transition arriving
        // milliseconds later can't race ahead of it.
        emitter.emitCreated()

        val appLifecycleListener = AppLifecycleStateListener(emitter)
        listener = appLifecycleListener
        get(context).appLifecycle.registerListener(appLifecycleListener)
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        listener?.let {
            get(context).appLifecycle.unregisterListener(it)
            listener = null
        }
    }

    override val name: String = "applifecycle"
}
