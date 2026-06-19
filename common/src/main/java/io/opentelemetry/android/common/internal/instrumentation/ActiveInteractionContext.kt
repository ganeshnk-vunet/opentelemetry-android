/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope

/**
 * Holds the active OpenTelemetry context for a single user interaction (for example a navigation
 * span after screen transition) so downstream async work can parent correctly. Cleared when a new
 * click interaction starts or instrumentation uninstalls.
 */
object ActiveInteractionContext {
    private val lock = Any()
    private var scope: Scope? = null

    fun activate(span: Span) {
        synchronized(lock) {
            scope?.close()
            scope = span.makeCurrent()
        }
    }

    fun clear() {
        synchronized(lock) {
            scope?.close()
            scope = null
        }
    }
}
