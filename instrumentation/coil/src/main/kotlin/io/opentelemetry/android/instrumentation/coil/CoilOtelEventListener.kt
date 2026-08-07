/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.coil

import coil.EventListener
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.target.Target
import coil.target.ViewTarget
import io.opentelemetry.android.common.internal.imageload.ImageLoadAttributes
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * A Coil [EventListener] that wraps every image request in an OpenTelemetry "image.load" span.
 *
 * ## Lifecycle contract
 * | Callback     | Action                                                          |
 * |--------------|-----------------------------------------------------------------|
 * | [onStart]    | Start span, sanitize URL, store in [CoilSpanStore]              |
 * | [onSuccess]  | Add source/status attrs, set OK status, end span                |
 * | [onError]    | Record exception, set ERROR status, end span                    |
 * | [onCancel]   | Set CANCELLED status, end span                                  |
 *
 * ## No cross-thread scope
 * This listener intentionally does **not** call [io.opentelemetry.api.trace.Span.makeCurrent].
 * Coil's [EventListener] callbacks can run on different threads (`onStart` on the caller thread,
 * terminal callbacks on a dispatcher thread), and an OTel [io.opentelemetry.context.Scope] must be
 * closed on the same thread that opened it. Storing a cross-thread scope risks corrupting the
 * originating thread's [ThreadLocal] context.
 *
 * OkHttp child-span parenting is instead handled by [VunetCoilInterceptor], which looks up the
 * span in [CoilSpanStore] and propagates it via `withContext(span.asContextElement())` on the
 * coroutine dispatcher — the same thread OkHttp executes on. This mirrors the Glide approach
 * (context restored on the worker thread) and avoids the cross-thread scope hazard entirely.
 *
 * ## URL sanitisation
 * Query parameters are stripped from the image URL before it is recorded as an attribute (see
 * [ImageLoadAttributes.sanitizeUrl]). This prevents authentication tokens, signed-URL parameters,
 * and other PII from leaking into the telemetry back-end — a hard requirement for BFSI apps.
 *
 * @param tracer the OTel [Tracer] used to create spans.
 */
internal class CoilOtelEventListener(
    private val tracer: Tracer,
) : EventListener {

    /**
     * Called by Coil immediately after an [ImageRequest] is enqueued.
     * This is the earliest hook available from an [EventListener] and marks the true start of
     * the image-loading pipeline. The span is started here and stored in [CoilSpanStore]; it is
     * **not** made current (see class KDoc).
     */
    override fun onStart(request: ImageRequest) {
        try {
            val key = System.identityHashCode(request)

            // If a span already exists for this request instance (e.g. Coil retry), end the stale
            // one before creating a fresh span.
            CoilSpanStore.spans.remove(key)?.let { stale ->
                try { stale.end() } catch (_: Throwable) {}
            }

            val imageUrl = ImageLoadAttributes.sanitizeUrl(request.data.toString())

            val span =
                tracer
                    .spanBuilder(IMAGE_LOAD_SPAN_NAME)
                    .setAttribute(ATTR_IMAGE_URL, imageUrl)
                    .setAttribute(ATTR_IMAGE_MODEL_TYPE, request.data.javaClass.name)
                    .setTargetAttributes(request.target)
                    .startSpan()

            CoilSpanStore.spans[key] = span
        } catch (_: Throwable) {
            // Telemetry failures must never interrupt the image loading pipeline.
        }
    }

    /**
     * Called by Coil when an image is successfully loaded. The [DataSource] from [SuccessResult]
     * is mapped to a canonical source-label string.
     */
    override fun onSuccess(
        request: ImageRequest,
        result: SuccessResult,
    ) {
        val key = System.identityHashCode(request)
        try {
            val span = CoilSpanStore.spans.remove(key)
            span?.let {
                it.setAttribute(ATTR_IMAGE_SOURCE, result.dataSource.toSourceLabel())
                it.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_SUCCESS)
                it.setStatus(StatusCode.OK)
                it.end()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Called by Coil when an image request fails for any reason. The throwable from [ErrorResult]
     * is recorded on the span so the full stack trace is available in the telemetry back-end.
     *
     * Unlike the Glide listener, this does **not** synthesise a span when the store holds no entry,
     * and the asymmetry is structural rather than drift. Glide skips `buildLoadData` entirely for
     * memory-cache hits, uncovered model types, and null models, so "no span was pre-created" is a
     * routine Glide state that would otherwise drop real failures. Coil dispatches [onStart] for
     * every request *before* consulting its memory cache, so a missing entry here means [onStart]
     * itself failed — in which case the tracer needed to synthesise a replacement is equally
     * suspect, and inventing a span with no start time would report a fabricated duration.
     */
    override fun onError(
        request: ImageRequest,
        result: ErrorResult,
    ) {
        val key = System.identityHashCode(request)
        try {
            val span = CoilSpanStore.spans.remove(key)
            span?.let {
                it.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_ERROR)
                it.setAttribute(ATTR_ERROR_TYPE, ImageLoadAttributes.errorType(result.throwable))
                it.recordException(result.throwable)
                it.setStatus(StatusCode.ERROR)
                it.end()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Called by Coil when a request is cancelled — e.g. fast scrolling, `DisposableEffect` cleanup,
     * [ImageRequest] disposal, or navigating away before the load completes. Coil invokes this
     * instead of [onError], so without handling it the span (and previously its scope) would be
     * orphaned in [CoilSpanStore] until SDK teardown.
     *
     * The span is ended with [STATUS_CANCELLED] and an UNSET span status so cancellations are
     * distinguishable from real failures in dashboards.
     */
    override fun onCancel(request: ImageRequest) {
        val key = System.identityHashCode(request)
        try {
            val span = CoilSpanStore.spans.remove(key)
            span?.let {
                it.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_CANCELLED)
                // Leave the span status UNSET: a cancellation is not an error.
                it.end()
            }
        } catch (_: Throwable) {}
    }
}

/**
 * Records which widget the image is being loaded into, when the request has a view-backed target
 * (`imageView.load(url)`, `AsyncImage` with a `ViewTarget`, …). Without this, a screen showing many
 * images produces failure spans that are indistinguishable from one another.
 *
 * Compose targets and `ImageRequest`s built without a target simply carry no view attributes.
 *
 * Reading [android.view.View.getId] here is safe: Coil dispatches `onStart` on
 * `Dispatchers.Main.immediate`, so this runs on the thread that owns the view.
 */
private fun SpanBuilder.setTargetAttributes(target: Target?): SpanBuilder {
    val view = (target as? ViewTarget<*>)?.view ?: return this
    return setAttribute(ATTR_IMAGE_TARGET_VIEW_ID, ImageLoadAttributes.resolveViewId(view))
        .setAttribute(ATTR_IMAGE_TARGET_VIEW_TYPE, view.javaClass.name)
}

/**
 * Maps a Coil [DataSource] to a canonical source-label string.
 *
 * | Coil DataSource   | Label     |
 * |-------------------|-----------|
 * | MEMORY            | "memory"  |
 * | DISK              | "disk"    |
 * | NETWORK           | "network" |
 */
private fun DataSource.toSourceLabel(): String =
    when (this) {
        DataSource.MEMORY, DataSource.MEMORY_CACHE -> SOURCE_MEMORY
        DataSource.DISK -> SOURCE_DISK
        DataSource.NETWORK -> SOURCE_NETWORK
    }
