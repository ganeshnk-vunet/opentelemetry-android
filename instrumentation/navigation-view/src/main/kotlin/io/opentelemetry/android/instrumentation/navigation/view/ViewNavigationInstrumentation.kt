/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

import android.app.Application
import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.common.Constants.INSTRUMENTATION_SCOPE
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter

@AutoService(AndroidInstrumentation::class)
class ViewNavigationInstrumentation : AndroidInstrumentation {
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    override val name: String = "navigation.view"

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        if (activityLifecycleCallbacks != null) {
            return
        }
        RumDiagnostics.d { "navigationView: install" }
        val tracer = openTelemetryRum.openTelemetry.getTracer(INSTRUMENTATION_SCOPE)
        val callback = ViewNavigationCollector(NavigationSpanEmitter(tracer), openTelemetryRum.clock)
        activityLifecycleCallbacks = callback
        ViewNavigationCollectorHolder.set(callback)
        (context as? Application)?.registerActivityLifecycleCallbacks(callback)
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        val callback = activityLifecycleCallbacks ?: return
        (context as? Application)?.unregisterActivityLifecycleCallbacks(callback)
        (callback as? ViewNavigationCollector)?.cleanup()
        ViewNavigationCollectorHolder.clear()
        activityLifecycleCallbacks = null
        NavigationSpanEmitter.clearActiveContext()
    }
}
