/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.interaction

import io.opentelemetry.context.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-global holder for the "active user interaction" [Context] (e.g. the `ui.click` span that
 * the user just triggered).
 *
 * ## Why this exists
 * OpenTelemetry's [Context] propagates through a thread-local. A click span made current on the
 * Android main thread is therefore invisible to network/navigation work that runs on other threads
 * (coroutine dispatchers, OkHttp's dispatcher pool). Apps can bridge this manually with
 * `Context.current().asContextElement()`, but that requires touching every call site and cannot be
 * enforced across arbitrary host apps.
 *
 * This holder offers a thread-agnostic fallback: hybrid-click publishes the click context here, and
 * instrumentation that finds no active context on its own thread (i.e. the OpenTelemetry
 * [Context.current] is [Context.root]) can adopt the published interaction as the parent. This keeps
 * click -> network -> navigation in a single trace without any host-app cooperation.
 *
 * A single slot models "the current interaction". Entries carry an expiry so a stale click cannot
 * parent a much later, unrelated call.
 */
object ActiveInteractionHolder {
    private data class Entry(
        val context: Context,
        val expiryNanos: Long,
    )

    private val current = AtomicReference<Entry?>(null)

    /**
     * Publishes [context] as the active interaction for the next [ttlMillis] milliseconds,
     * replacing any previously active interaction.
     */
    fun set(
        context: Context,
        ttlMillis: Long,
    ) {
        current.set(Entry(context, System.nanoTime() + ttlMillis * NANOS_PER_MILLI))
    }

    /**
     * Clears the active interaction, but only if it still refers to [context]. The identity check
     * prevents an ending interaction from clobbering a newer one that started in the meantime.
     */
    fun clear(context: Context) {
        val existing = current.get() ?: return
        if (existing.context === context) {
            current.compareAndSet(existing, null)
        }
    }

    /**
     * Returns the active interaction [Context], or `null` when none is set or it has expired.
     * Expired entries are cleared lazily on read.
     */
    fun current(): Context? {
        val existing = current.get() ?: return null
        if (System.nanoTime() > existing.expiryNanos) {
            current.compareAndSet(existing, null)
            return null
        }
        return existing.context
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
