/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import io.opentelemetry.api.trace.Span
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe store shared between [OtelSideEffectModelLoader] (which starts spans) and
 * [VunetGlideRequestListener] (which ends them).
 *
 * Keys are the identity hash of the Glide `model` object so that two concurrent requests to
 * the same URL (same String content, different instances) are tracked independently.
 * [System.identityHashCode] collisions are theoretically possible but astronomically rare;
 * the worst outcome is a missed span — never a crash.
 *
 * Unlike Coil, Glide spans are started on the main thread but the [io.opentelemetry.context.Scope]
 * is opened and closed on Glide's background executor thread inside [OtelContextDataFetcher]
 * via `capturedContext.makeCurrent().use { ... }`. No scope is stored here because it never
 * crosses thread boundaries — it is always closed by the same thread that opens it.
 *
 * ## Span timing
 * The span start timestamp is set in [OtelContextModelLoader.buildLoadData] via
 * `setStartTimestamp(System.currentTimeMillis() * 1_000_000, …)`. This is wall-clock time at
 * **millisecond** resolution (the `* 1_000_000` only rescales ms → ns; it does not add sub-ms
 * precision). For RUM this is acceptable, but very fast loads such as memory-cache hits may
 * report a near-zero duration in the backend. See the README "Known limitations" section.
 */
internal object GlideSpanStore {
    /**
     * Hard cap on tracked in-flight spans. Cancelled Glide requests are normally cleaned up by
     * [OtelContextDataFetcher.cancel], but cancellations that occur before the fetcher is built
     * (or after fetch, during decode) have no callback, so the store must not grow unboundedly
     * over a long session. Well above any realistic number of concurrent image requests.
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
}
