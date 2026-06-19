/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav2

import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.navigation.common.NavigationSpanEmitter

@AutoService(AndroidInstrumentation::class)
class ComposeNav2Instrumentation : AndroidInstrumentation {
    override val name: String = "navigation.compose.nav2"

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        RumDiagnostics.d { "navigationComposeNav2: install" }
        NavObserverRumHolder.set(openTelemetryRum)
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        NavObserverRumHolder.clear()
        NavigationSpanEmitter.clearActiveContext()
    }
}
