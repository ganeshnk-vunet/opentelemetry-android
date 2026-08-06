/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context as OtelContext
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Factory that produces [OtelContextModelLoader] instances for a specific model type.
 *
 * Passes the delegate [ModelLoader] (all other loaders registered for this specific Model → InputStream)
 * into the loader so we can wrap its [DataFetcher] for context propagation.
 * Glide's [MultiModelLoaderFactory] skips the factory currently being constructed when resolving
 * the delegate, preventing infinite recursion.
 */
internal class OtelSideEffectModelLoaderFactory<Model : Any>(
    private val tracer: Tracer,
    private val modelClass: Class<Model>,
) : ModelLoaderFactory<Model, InputStream> {
    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Model, InputStream> {
        val delegate = multiFactory.build(modelClass, InputStream::class.java)
        return OtelContextModelLoader(tracer, delegate)
    }

    override fun teardown() {}
}

/**
 * A delegating [ModelLoader] that:
 * 1. Asks the real loader chain for a [ModelLoader.LoadData].
 * 2. Starts an "image.load" OTel span on the **main thread**.
 * 3. Captures the current OTel [Context][OtelContext] containing the span.
 * 4. Returns a [ModelLoader.LoadData] whose fetcher is wrapped in [OtelContextDataFetcher],
 *    which restores the captured context on Glide's **background thread** before the real
 *    HTTP fetch runs — making OkHttp HTTP spans children of the image.load span.
 */
internal class OtelContextModelLoader<Model : Any>(
    private val tracer: Tracer,
    private val delegate: ModelLoader<Model, InputStream>,
) : ModelLoader<Model, InputStream> {

    override fun handles(model: Model): Boolean = delegate.handles(model)

    override fun buildLoadData(
        model: Model,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream>? {
        // Prevent nested interception: Glide loaders often delegate (e.g. String -> Uri -> GlideUrl).
        // If we intercept all of them, we create multiple spans for one request, and the innermost
        // span's context is propagated to OkHttp, but the RequestListener only receives the outermost
        // model to close the span. This orphans the inner spans.
        // By guarding this, only the outermost model creates the span and wraps the fetcher.
        if (isBuilding.get() == true) {
            return delegate.buildLoadData(model, width, height, options)
        }

        isBuilding.set(true)
        val delegateData = try {
            delegate.buildLoadData(model, width, height, options)
        } finally {
            isBuilding.remove()
        }

        if (delegateData == null) return null

        // The tracer captured at registration time outlives uninstall() (Glide's Registry keeps
        // the loader for the process lifetime). Gate on the live companion field so that after
        // uninstall this loader degrades to a transparent pass-through instead of creating
        // spans with a stale tracer.
        if (GlideInstrumentation.tracer == null) return delegateData

        return try {
            val key = System.identityHashCode(model)
            // Span start timestamp in wall-clock epoch nanoseconds. Note this is millisecond
            // resolution: System.currentTimeMillis() * 1_000_000 only rescales ms → ns and does
            // not provide true sub-millisecond precision, so very fast loads may report a near-zero
            // duration. This is an accepted RUM tradeoff (documented in the README); finer
            // resolution would require pairing a System.nanoTime() delta with a clock offset.
            val startEpochNanos = System.currentTimeMillis() * 1_000_000

            // Clean up any stale in-flight span for this model instance (e.g. Glide retry).
            GlideSpanStore.spans.remove(key)?.let { stale ->
                try { stale.end() } catch (_: Throwable) {}
            }

            val span =
                tracer
                    .spanBuilder(IMAGE_LOAD_SPAN_NAME)
                    .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
                    .setAttribute(ATTR_IMAGE_URL, sanitizeModel(model.toString()))
                    .setAttribute(ATTR_IMAGE_MODEL_TYPE, model.javaClass.name)
                    .startSpan()

            // Capture the context AFTER starting the span so it contains the span.
            // This context snapshot is handed to OtelContextDataFetcher which restores
            // it on the background thread, propagating the parent span to OkHttp.
            val capturedContext = OtelContext.current().with(span)
            GlideSpanStore.put(key, span)

            ModelLoader.LoadData(
                delegateData.sourceKey,
                OtelContextDataFetcher(delegateData.fetcher, capturedContext, key),
            )
        } catch (_: Throwable) {
            // Telemetry failures must never interrupt the image loading pipeline.
            delegateData
        }
    }

    private companion object {
        private val isBuilding = ThreadLocal<Boolean>()
    }
}

/**
 * Wraps a real [DataFetcher] to restore a captured OTel [Context][OtelContext] before
 * `loadData` is called on Glide's background executor thread.
 *
 * This is the mechanism that makes HTTP spans (created by the OkHttp instrumentation on the
 * background thread) appear as children of the "image.load" span rather than as independent
 * root traces.
 *
 * [cancel] additionally ends the in-flight span: Glide's [com.bumptech.glide.request.RequestListener]
 * has **no** cancellation callback (unlike Coil's `onCancel`), so scroll-away / navigate-away
 * cancellations would otherwise orphan the span in [GlideSpanStore] for the process lifetime —
 * a memory leak in list-heavy apps. Cancellations that occur before the fetcher is built or
 * after the fetch completes are covered by the [GlideSpanStore] size cap instead.
 */
internal class OtelContextDataFetcher(
    private val delegate: DataFetcher<InputStream>,
    private val capturedContext: OtelContext,
    private val storeKey: Int,
) : DataFetcher<InputStream> {

    override fun loadData(
        priority: Priority,
        callback: DataFetcher.DataCallback<in InputStream>,
    ) {
        // Restore the image.load span context on this background thread so OkHttp spans
        // created during the fetch are children of the image.load span.
        capturedContext.makeCurrent().use {
            delegate.loadData(priority, callback)
        }
    }

    override fun cleanup() = delegate.cleanup()

    override fun cancel() {
        // End the span for a cancelled request; the RequestListener terminal callbacks will
        // never fire for it. remove() is atomic, so a rare race with a terminal callback ends
        // in exactly one of the two paths owning the span.
        try {
            GlideSpanStore.spans.remove(storeKey)?.let { span ->
                span.setAttribute(ATTR_IMAGE_LOAD_STATUS, STATUS_CANCELLED)
                // Leave the span status UNSET: a cancellation is not an error.
                span.end()
            }
        } catch (_: Throwable) {
            // Telemetry failures must never interrupt the image loading pipeline.
        }
        delegate.cancel()
    }

    override fun getDataClass(): Class<InputStream> = delegate.dataClass

    override fun getDataSource(): DataSource = delegate.dataSource
}
