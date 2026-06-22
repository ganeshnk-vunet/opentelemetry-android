/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency

import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.AndroidInstrumentation

/**
 * Compile-time concurrency context propagation via the concurrency-agent Byte Buddy plugin.
 *
 * <p>Runtime installation is a no-op; consumers must apply the Byte Buddy Gradle plugin and depend
 * on concurrency-agent so coroutine, executor, and Handler boundaries propagate OpenTelemetry
 * context automatically.
 */
@AutoService(AndroidInstrumentation::class)
class ConcurrencyInstrumentation : AndroidInstrumentation {
    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        // Weaving is applied at compile time by concurrency-agent.
    }

    override val name: String = "concurrency"
}
