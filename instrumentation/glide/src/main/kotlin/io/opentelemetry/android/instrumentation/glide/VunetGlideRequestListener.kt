/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.opentelemetry.android.common.internal.imageload.ImageLoadAttributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import java.util.concurrent.TimeUnit

/**
 * Global Glide [RequestListener] that **completes** the "image.load" OTel span started by
 * [OtelSideEffectModelLoader].
 *
 * ## Responsibility split
 * | Phase            | Class                        | Action                                        |
 * |------------------|------------------------------|-----------------------------------------------|
 * | buildLoadData    | [OtelSideEffectModelLoader]  | Start span (parent = StartupSpanProvider), store |
 * | Fetch runs       | [OtelContextDataFetcher]     | Restore context, OkHttp spans child           |
 * | Request finished | [VunetGlideRequestListener]   | Add attrs, end span                           |
 *
 * ## Scope lifecycle
 * Glide scopes are opened and closed on Glide's background executor thread inside
 * [OtelContextDataFetcher.loadData] via `capturedContext.makeCurrent().use { }`. They are
 * never stored in [GlideSpanStore] and require no cleanup here.
 *
 * ## Null-safety
 * The spans map is queried with `.remove()` — atomic retrieve-and-delete. If the ModelLoader did
 * not store an entry (e.g. Glide disabled our loader, or a prior exception), the methods return
 * silently without any side effects.
 *
 * ## Consumer registration (in AppGlideModule)
 * ```kotlin
 * override fun applyOptions(context: Context, builder: GlideBuilder) {
 *     builder.addGlobalRequestListener(VunetGlideRequestListener())
 * }
 * ```
 */
class VunetGlideRequestListener : RequestListener<Any> {

    override fun onResourceReady(
        resource: Any,
        model: Any,
        target: Target<Any>?,
        dataSource: DataSource,
        isFirstResource: Boolean,
    ): Boolean {
        // Capture as early as possible. For memory-cache hits Glide never calls buildLoadData,
        // so this is the only timestamp we have. The memory-cache path is fully synchronous
        // (Engine.load → onResourceReady runs without leaving the calling thread), so this
        // timestamp is within microseconds of when the request was submitted.
        val receivedAtEpochNanos = System.currentTimeMillis() * 1_000_000
        val key = System.identityHashCode(model)
        return try {
            val span: Span? = GlideSpanStore.spans.remove(key)
            if (span != null) {
                // Normal path: span was started by OtelContextModelLoader (disk / network)
                // with setStartTimestamp, so its duration already reflects real fetch time.
                span.setAttribute(ATTR_IMAGE_SOURCE, dataSource.toSourceLabel())
                span.setAttribute(ATTR_IMAGE_IS_FIRST_RESOURCE, isFirstResource)
                span.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_SUCCESS)
                span.setStatus(StatusCode.OK)
                span.end()
            } else if (dataSource == DataSource.MEMORY_CACHE) {
                // Memory-cache hit: Glide bypasses the ModelLoader entirely so no span was
                // pre-created. Use receivedAtEpochNanos as the start timestamp so the span has a
                // meaningful (sub-millisecond) duration rather than zero.
                val tracer = GlideInstrumentation.tracer ?: return false
                tracer.spanBuilder(IMAGE_LOAD_SPAN_NAME)
                    .setStartTimestamp(receivedAtEpochNanos, TimeUnit.NANOSECONDS)
                    .also { builder ->
                        urlForModel(model)?.let { builder.setAttribute(ATTR_IMAGE_URL, it) }
                    }
                    .setAttribute(ATTR_IMAGE_MODEL_TYPE, model.javaClass.name)
                    .startSpan()
                    .also { memSpan ->
                        memSpan.setAttribute(ATTR_IMAGE_SOURCE, SOURCE_MEMORY)
                        memSpan.setAttribute(ATTR_IMAGE_IS_FIRST_RESOURCE, isFirstResource)
                        memSpan.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_SUCCESS)
                        memSpan.setStatus(StatusCode.OK)
                        memSpan.end()
                    }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun onLoadFailed(
        e: GlideException?,
        model: Any?,
        target: Target<Any>,
        isFirstResource: Boolean,
    ): Boolean {
        val receivedAtEpochNanos = System.currentTimeMillis() * 1_000_000
        val key = model?.let { System.identityHashCode(it) } ?: return false
        return try {
            val span: Span? = GlideSpanStore.spans.remove(key)
            // If no pre-created span exists (e.g. failure on a memory-cache path),
            // create one on-the-fly so the failure is never silently dropped.
            // Use receivedAtEpochNanos as start so the span has a real (sub-millisecond) duration.
            val activeSpan = span ?: run {
                val tracer = GlideInstrumentation.tracer ?: return false
                tracer.spanBuilder(IMAGE_LOAD_SPAN_NAME)
                    .setStartTimestamp(receivedAtEpochNanos, TimeUnit.NANOSECONDS)
                    .also { builder ->
                        urlForModel(model)?.let { builder.setAttribute(ATTR_IMAGE_URL, it) }
                    }
                    .setAttribute(ATTR_IMAGE_MODEL_TYPE, model.javaClass.name)
                    .startSpan()
            }
            activeSpan.setAttribute(ATTR_IMAGE_IS_FIRST_RESOURCE, isFirstResource)
            activeSpan.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_ERROR)
            // Never Span.recordException here: GlideException aggregates root causes whose raw
            // messages routinely embed the full unsanitised URL (tokens included).
            if (e != null) ImageLoadAttributes.recordSanitizedException(activeSpan, e)
            activeSpan.setStatus(StatusCode.ERROR)
            activeSpan.end()
            false
        } catch (_: Throwable) {
            false
        }
    }
}

private fun DataSource.toSourceLabel(): String =
    when (this) {
        DataSource.MEMORY_CACHE -> SOURCE_MEMORY
        DataSource.LOCAL -> SOURCE_DISK
        DataSource.REMOTE -> SOURCE_NETWORK
        DataSource.DATA_DISK_CACHE, DataSource.RESOURCE_DISK_CACHE -> SOURCE_DISK_CACHE
    }

/**
 * Returns the sanitised URL for URL-like models, or null for everything else.
 *
 * The global [RequestListener] receives **every** Glide model — including [java.io.File],
 * `byte[]`, resource IDs, and arbitrary custom model classes whose `toString()` may embed
 * customer data (PII) or, for base64 `data:` URI strings, hundreds of kilobytes of payload.
 * Only models that are genuinely URL-shaped are recorded as `image.url`; all other types are
 * identified by `image.model_type` alone.
 */
private fun urlForModel(model: Any): String? =
    when (model) {
        is String, is android.net.Uri, is java.net.URL, is GlideUrl ->
            sanitizeModel(model.toString())
        else -> null
    }
