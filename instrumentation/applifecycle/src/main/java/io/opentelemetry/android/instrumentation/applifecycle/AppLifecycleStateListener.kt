/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import io.opentelemetry.android.internal.services.applifecycle.ApplicationStateListener

/** Forwards real app-level transitions to the [AppLifecycleSpanEmitter]. */
internal class AppLifecycleStateListener(
    private val emitter: AppLifecycleSpanEmitter,
) : ApplicationStateListener {
    override fun onApplicationForegrounded() = emitter.emitForeground()

    override fun onApplicationBackgrounded() = emitter.emitBackground()
}
