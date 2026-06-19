/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.app.Application
import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.ConfigurableHybridClickInstrumentation

@AutoService(AndroidInstrumentation::class)
/**
 * Android instrumentation entry point that installs hybrid click tracking.
 *
 * This module captures click/tap interactions from both traditional View hierarchies and
 * Jetpack Compose by registering activity lifecycle callbacks.
 */
class HybridClickInstrumentation : AndroidInstrumentation, ConfigurableHybridClickInstrumentation {
    override val name: String = "hybrid.click"
    private var activeContextWindowMillis: Long = DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS
    private var activityLifecycleCallback: Application.ActivityLifecycleCallbacks? = null

    /**
     * Creates the tracer used for hybrid click spans and registers lifecycle callbacks that
     * attach/detach per-window touch interception as activities resume/pause.
     */
    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        if (activityLifecycleCallback != null) {
            return
        }
        RumDiagnostics.d { "hybridClick: install" }

        val tracer =
            openTelemetryRum.openTelemetry
                .tracerProvider
                .tracerBuilder("io.opentelemetry.android.instrumentation.hybrid.click")
                .build()

        (context as? Application)?.let { application ->
            val callback =
                ClickActivityCallback(
                    ClickEventGenerator(
                        tracer = tracer,
                        activeContextWindowMillis = activeContextWindowMillis,
                    ),
                )
            activityLifecycleCallback = callback
            application.registerActivityLifecycleCallbacks(callback)
        }
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        activityLifecycleCallback?.let { callback ->
            (context as? Application)?.unregisterActivityLifecycleCallbacks(callback)
        }
        activityLifecycleCallback = null
        ActiveInteractionContext.clear()
    }

    /**
     * Configures how long the click span stays active to correlate downstream async work.
     */
    override fun setActiveContextWindowMillis(value: Long) {
        activeContextWindowMillis = value
    }

    private companion object {
        const val DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS = 500L
    }
}
