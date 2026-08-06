/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.coil

import io.opentelemetry.api.trace.Span
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe store shared between [CoilOtelEventListener] (which starts spans) and
 * [VunetCoilInterceptor] (which reads the span to propagate its context to OkHttp), plus the
 * listener's own terminal callbacks (which end them).
 *
 * Keys are the identity hash of the Coil [coil.request.ImageRequest] object so that two concurrent
 * requests to the same URL (same String content, different instances) are tracked independently.
 * [System.identityHashCode] collisions are theoretically possible but astronomically rare;
 * the worst outcome is a missed span — never a crash.
 *
 * No OTel [io.opentelemetry.context.Scope] is stored here: [CoilOtelEventListener] deliberately
 * does not call `makeCurrent()` (see its KDoc), so there is no cross-thread scope to manage.
 *
 * [drain] is called during [CoilInstrumentation.uninstall] to guarantee that all orphaned in-flight
 * spans are ended.
 */
internal object CoilSpanStore {
    /**
     * Hard cap on tracked in-flight spans. Coil's [CoilOtelEventListener.onCancel] normally
     * guarantees every entry is removed, but if a terminal callback is ever missed the store
     * must not grow unboundedly over a long session. Well above any realistic number of
     * concurrent image requests.
     */
    private const val MAX_TRACKED_SPANS = 200

    val spans: ConcurrentHashMap<Int, Span> = ConcurrentHashMap()

    /**
     * Stores [span] under [key]. If the store is at [MAX_TRACKED_SPANS], an arbitrary entry is
     * evicted and ended first so the map stays bounded (leak safety net).
     */
    fun put(
        key: Int,
        span: Span,
    ) {
        if (spans.size >= MAX_TRACKED_SPANS) {
            val staleKey = spans.keys.firstOrNull()
            if (staleKey != null) {
                spans.remove(staleKey)?.let { stale ->
                    try { stale.end() } catch (_: Throwable) {}
                }
            }
        }
        spans[key] = span
    }

    /**
     * Ends all stored [Span] entries, then clears the map.
     * Designed to be called exactly once during SDK teardown.
     */
    fun drain() {
        spans.values.forEach { span ->
            try { span.end() } catch (_: Throwable) {}
        }
        spans.clear()
    }
}
