/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomViewTarget
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
 * The spans map is queried with `.remove()` — atomic retrieve-and-delete. When the ModelLoader did
 * not store an entry (memory-cache hit, an uncovered model type, or a failure before
 * `buildLoadData` ran) a span is synthesised here so the outcome is still reported.
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
        return try {
            // Normal path: span was started by OtelContextModelLoader (disk / network) with
            // setStartTimestamp, so its duration already reflects real fetch time.
            val span =
                GlideSpanStore.spans.remove(System.identityHashCode(model))
                    ?: if (dataSource == DataSource.MEMORY_CACHE) {
                        // Glide bypasses the ModelLoader entirely for memory-cache hits, so no span
                        // was pre-created. receivedAtEpochNanos gives the synthesised span a
                        // meaningful (sub-millisecond) duration rather than zero.
                        startSyntheticSpan(model, receivedAtEpochNanos) ?: return false
                    } else {
                        return false
                    }

            span.setAttribute(ATTR_IMAGE_SOURCE, dataSource.toSourceLabel())
            span.setAttribute(ATTR_IMAGE_IS_FIRST_RESOURCE, isFirstResource)
            span.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_SUCCESS)
            span.setTargetAttributes(target)
            span.setStatus(StatusCode.OK)
            span.end()
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
        return try {
            // A failure must never be dropped: when no span was pre-created — memory-cache path, an
            // uncovered model type, or a null model such as `load(null)` — synthesise one so the
            // failure still reaches the back-end.
            val span =
                model?.let { GlideSpanStore.spans.remove(System.identityHashCode(it)) }
                    ?: startSyntheticSpan(model, receivedAtEpochNanos)
                    ?: return false

            span.setAttribute(ATTR_IMAGE_IS_FIRST_RESOURCE, isFirstResource)
            span.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_ERROR)
            span.setTargetAttributes(target)
            if (e != null) {
                span.setAttribute(ATTR_ERROR_TYPE, e.rootErrorType())
                span.recordException(e)
            }
            span.setStatus(StatusCode.ERROR)
            span.end()
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Starts an "image.load" span for a request that never passed through
     * [OtelContextModelLoader]. [startEpochNanos] is the moment the terminal callback was entered,
     * so the span still has a non-zero duration.
     *
     * Returns `null` — and therefore reports nothing — when the SDK has not installed a tracer.
     */
    private fun startSyntheticSpan(
        model: Any?,
        startEpochNanos: Long,
    ): Span? {
        val tracer = GlideInstrumentation.tracer ?: return null
        return tracer
            .spanBuilder(IMAGE_LOAD_SPAN_NAME)
            .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
            .setAttribute(ATTR_IMAGE_URL, model?.let { sanitizeModel(it.toString()) } ?: VALUE_UNKNOWN)
            .setAttribute(ATTR_IMAGE_MODEL_TYPE, model?.javaClass?.name ?: VALUE_UNKNOWN)
            .startSpan()
    }
}

/**
 * Records which widget the image was being loaded into. Glide hands the [Target] to every terminal
 * callback but the model alone cannot tell two `ImageView`s on the same screen apart — this is what
 * makes a failure actionable ("`avatar_image` on ProfileFragment failed").
 *
 * Both view-backed target hierarchies are covered: `ViewTarget` — deprecated in Glide, but still
 * the base of `ImageViewTarget` and therefore of every `into(imageView)` call, hence the
 * suppression — and the newer [CustomViewTarget]. Non-view targets — `submit()`, `preload()`,
 * custom [Target] implementations — carry no view attributes.
 */
@Suppress("DEPRECATION")
private fun Span.setTargetAttributes(target: Target<*>?) {
    val view =
        when (target) {
            is com.bumptech.glide.request.target.ViewTarget<*, *> -> target.view
            is CustomViewTarget<*, *> -> target.view
            else -> null
        } ?: return
    setAttribute(ATTR_IMAGE_TARGET_VIEW_ID, ImageLoadAttributes.resolveViewId(view))
    setAttribute(ATTR_IMAGE_TARGET_VIEW_TYPE, view.javaClass.name)
}

/**
 * Glide wraps every failure in a [GlideException] whose message is a generic "Failed to load
 * resource". Reporting that class name would make every failure look identical, so the first root
 * cause — the real `HttpException`, `SocketTimeoutException`, `FileNotFoundException`, … — is used
 * when Glide recorded one.
 *
 * Glide tries each registered data-fetch/decode path and records a root cause per failed attempt,
 * so a single failure can carry several. Taking the first is deterministic (Glide appends in
 * attempt order, so it is the earliest failing path) but is not guaranteed to be the most
 * actionable one — a load that fails the network fetch *and* then a fallback decode reports the
 * network error. Ranking causes by "interestingness" was considered and rejected: any ordering
 * would encode a guess about which layer the reader cares about, and the full set is already on
 * the span via `recordException`, whose stack trace preserves every cause.
 */
private fun GlideException.rootErrorType(): String =
    ImageLoadAttributes.errorType(rootCauses?.firstOrNull() ?: this)

private fun DataSource.toSourceLabel(): String =
    when (this) {
        DataSource.MEMORY_CACHE -> SOURCE_MEMORY
        DataSource.LOCAL -> SOURCE_DISK
        DataSource.REMOTE -> SOURCE_NETWORK
        DataSource.DATA_DISK_CACHE, DataSource.RESOURCE_DISK_CACHE -> SOURCE_DISK_CACHE
    }
