/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.instrumentation

import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope

/**
 * Holds the active OpenTelemetry context for a single user interaction (for example a navigation
 * span after screen transition) so downstream async work can parent correctly. Cleared when a new
 * click interaction starts or instrumentation uninstalls.
 */
object ActiveInteractionContext {
    private val lock = Any()
    private var scope: Scope? = null
    private var rootContext: Context? = null
    private var generation: Long = 0
    private var ownerThread: Thread? = null

    /** Starts a new interaction rooted at [root] (for example `ui.click`). Clears any stale interaction. */
    fun begin(root: Span): Long =
        synchronized(lock) {
            warnIfForeignThread()
            scope?.close()
            ownerThread = Thread.currentThread()
            scope = root.makeCurrent()
            rootContext = Context.current()
            ++generation
        }

    /** Replaces the active parent within the current interaction (for example `ui.navigation`). */
    fun activate(span: Span) {
        synchronized(lock) {
            warnIfForeignThread()
            scope?.close()
            scope = span.makeCurrent()
        }
    }

    /** Parent context for spans created explicitly within the current interaction (for example nav under click). */
    fun rootContext(): Context? = synchronized(lock) { rootContext }

    /** Ends the interaction identified by [token] only if it is still current (guards rapid taps). */
    fun end(token: Long) {
        synchronized(lock) {
            if (token == generation) {
                clearLocked()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        warnIfForeignThread()
        scope?.close()
        scope = null
        rootContext = null
        ownerThread = null
    }

    private fun warnIfForeignThread() {
        val owner = ownerThread
        if (owner != null && Thread.currentThread() !== owner) {
            RumDiagnostics.d {
                "ActiveInteractionContext: cross-thread access; scope semantics may be unreliable"
            }
        }
    }
}
