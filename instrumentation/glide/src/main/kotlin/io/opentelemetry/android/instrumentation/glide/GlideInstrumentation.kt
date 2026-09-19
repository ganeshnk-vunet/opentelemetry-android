/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import android.content.Context
import com.google.auto.service.AutoService
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock

/**
 * [AndroidInstrumentation] entry point that activates OpenTelemetry image-load telemetry for
 * Glide image requests.
 *
 * ## How it works
 * 1. [install] stores a [Tracer] in [tracer] (companion object, `@Volatile`).
 * 2. [GlideOtelModule.registerComponents] reads [tracer] and injects [OtelSideEffectModelLoader]
 *    into Glide's component registry — this loader starts an "image.load" span and calls
 *    [io.opentelemetry.api.trace.Span.makeCurrent] for every request.
 * 3. The consumer's `AppGlideModule.applyOptions` registers [VunetGlideRequestListener] globally —
 *    this listener closes the [io.opentelemetry.context.Scope], adds outcome attributes, and ends
 *    the span in `onResourceReady`/`onLoadFailed`.
 *
 * ## Consumer registration (one-time setup in AppGlideModule)
 * ```kotlin
 * @GlideModule
 * class MyAppGlideModule : AppGlideModule() {
 *     override fun applyOptions(context: Context, builder: GlideBuilder) {
 *         builder.addGlobalRequestListener(VunetGlideRequestListener())
 *     }
 *     // registerComponents() override is NOT required — GlideOtelModule handles
 *     // component registration automatically via Glide's LibraryGlideModule discovery.
 * }
 * ```
 *
 * Idempotency: a second call to [install] while the instrumentation is active is a no-op.
 * [uninstall] resets the tracer and ends any in-flight spans remaining in [GlideSpanStore].
 */
@AutoService(AndroidInstrumentation::class)
class GlideInstrumentation : AndroidInstrumentation {
    override val name: String = "glide"

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        if (tracer != null) {
            return
        }
        clock = openTelemetryRum.clock
        tracer =
            openTelemetryRum.openTelemetry
                .tracerProvider
                .tracerBuilder("io.opentelemetry.android.instrumentation.glide")
                .build()
    }

    override fun uninstall(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {
        tracer = null
        clock = null
        // End any in-flight spans. Glide scopes are opened and closed on Glide's background
        // executor thread inside OtelContextDataFetcher.loadData() via .use { }, so they are
        // never stored here and require no cleanup.
        GlideSpanStore.spans.values.forEach { span ->
            try { span.end() } catch (_: Throwable) {}
        }
        GlideSpanStore.spans.clear()
    }

    companion object {
        /**
         * Shared tracer read by [GlideOtelModule] during Glide's independent singleton init.
         * Written once on the main thread in [install]; `@Volatile` ensures cross-thread
         * visibility without synchronisation overhead.
         */
        @Volatile
        @JvmField
        internal var tracer: Tracer? = null

        /**
         * The SDK's own clock, captured at install so explicit timestamps land in the same time
         * domain as every implicitly stamped span.
         *
         * This matters more than it looks. `OtelAndroidClock.now()` is a fixed per-process baseline
         * plus `SystemClock.elapsedRealtimeNanos()`, so it is **monotonic** — a start and an end
         * both read from it can never invert. `System.currentTimeMillis()` is neither monotonic nor
         * in that domain: it is corrected by NTP, and it is anchored differently from the SDK
         * baseline, which is what produced `image.load` spans ending before they started.
         */
        @Volatile
        internal var clock: Clock? = null

        /**
         * Registers the OTel model-loader factory into Glide's [Registry].
         *
         * [GlideOtelModule] already calls this automatically when Glide initialises its
         * component registry. Calling it again from your own
         * [com.bumptech.glide.module.AppGlideModule.registerComponents] is therefore
         * **not required** for the standard setup.
         *
         * The only reason to call it explicitly is if you are constructing a custom
         * [com.bumptech.glide.GlideBuilder] that bypasses Glide's auto-discovery of
         * [com.bumptech.glide.module.LibraryGlideModule] subclasses (unusual in production apps).
         *
         * No-op if [GlideInstrumentation] has not been [install]ed yet.
         */
        fun registerGlideComponents(registry: com.bumptech.glide.Registry) {
            val currentTracer = tracer ?: return

            // Register intercepting ModelLoaders for the specific types Glide uses for
            // networking. This guarantees our delegating factory receives the underlying
            // OkHttp/HttpUrlConnection loaders when it asks Glide's MultiModelLoaderFactory
            // for the delegate.
            registry.prepend(
                String::class.java,
                java.io.InputStream::class.java,
                OtelSideEffectModelLoaderFactory(currentTracer, String::class.java),
            )
            registry.prepend(
                com.bumptech.glide.load.model.GlideUrl::class.java,
                java.io.InputStream::class.java,
                OtelSideEffectModelLoaderFactory(
                    currentTracer,
                    com.bumptech.glide.load.model.GlideUrl::class.java,
                ),
            )
            registry.prepend(
                java.net.URL::class.java,
                java.io.InputStream::class.java,
                OtelSideEffectModelLoaderFactory(currentTracer, java.net.URL::class.java),
            )
            registry.prepend(
                android.net.Uri::class.java,
                java.io.InputStream::class.java,
                OtelSideEffectModelLoaderFactory(currentTracer, android.net.Uri::class.java),
            )
        }
    }
}
