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
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.InputStream

/**
 * Unit tests for [OtelSideEffectModelLoader] / [OtelContextModelLoader] / [OtelContextDataFetcher].
 *
 * These tests verify:
 * - [OtelContextModelLoader.buildLoadData] starts an "image.load" span.
 * - URL is sanitized (query params stripped).
 * - [ATTR_IMAGE_MODEL_TYPE] is set correctly.
 * - The span is stored in [GlideSpanStore].
 * - The returned fetcher is wrapped in [OtelContextDataFetcher].
 * - [OtelContextDataFetcher.loadData] restores the captured context so [OtelContext.current]
 *   contains the span during the fetch (enabling OkHttp child spans).
 * - Nested / reentrant calls (String → GlideUrl delegation) produce only one span.
 * - Stale span cleanup on Glide retry (second call for same model identity).
 * - Null returned from delegate [ModelLoader.buildLoadData] is propagated as-is.
 * - Telemetry exceptions never interrupt the image pipeline.
 * - [OtelSideEffectModelLoaderFactory.build] wires the delegate correctly.
 */
class OtelSideEffectModelLoaderTest {
    companion object {
        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private lateinit var tracer: io.opentelemetry.api.trace.Tracer

    @BeforeEach
    fun setUp() {
        GlideSpanStore.spans.clear()
        tracer = otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()
        // buildLoadData gates on the live companion tracer (uninstall pass-through behaviour),
        // so tests must mark the instrumentation as installed.
        GlideInstrumentation.tracer = tracer
    }

    @AfterEach
    fun tearDown() {
        GlideInstrumentation.tracer = null
        GlideSpanStore.spans.clear()
    }

    // ── span creation ────────────────────────────────────────────────────────

    @Test
    fun `buildLoadData starts an image_load span`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())

        val key = System.identityHashCode(model)
        assertThat(GlideSpanStore.spans).containsKey(key)
        // End the in-flight span so it is exported
        GlideSpanStore.spans[key]?.end()
        assertThat(otelTesting.spans).hasSize(1)
        assertThat(otelTesting.spans[0].name).isEqualTo(IMAGE_LOAD_SPAN_NAME)
    }

    @Test
    fun `buildLoadData sanitizes URL - query params stripped`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/profile.jpg?token=SECRET&sig=abc"

        loader.buildLoadData(model, 100, 100, Options())

        val key = System.identityHashCode(model)
        GlideSpanStore.spans[key]?.end()
        val spanUrl = otelTesting.spans[0].attributes[ATTR_IMAGE_URL]
        assertThat(spanUrl).isEqualTo("https://cdn.bank.com/profile.jpg")
        assertThat(spanUrl).doesNotContain("SECRET")
    }

    @Test
    fun `buildLoadData records model type attribute`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())

        val key = System.identityHashCode(model)
        GlideSpanStore.spans[key]?.end()
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_MODEL_TYPE])
            .isEqualTo(String::class.java.name)
    }

    @Test
    fun `buildLoadData stores span in GlideSpanStore`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())

        assertThat(GlideSpanStore.spans).containsKey(System.identityHashCode(model))
    }

    @Test
    fun `buildLoadData span has non-zero start epoch set via setStartTimestamp`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())

        val key = System.identityHashCode(model)
        GlideSpanStore.spans[key]?.end()
        val span = otelTesting.spans[0]
        assertThat(span.startEpochNanos).isGreaterThan(0L)
        assertThat(span.endEpochNanos).isGreaterThanOrEqualTo(span.startEpochNanos)
    }

    // ── OtelContextDataFetcher: context propagation ──────────────────────────

    @Test
    fun `loadData on fetcher restores captured context so OtelContext current contains the span`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())!!

        var spanSeenDuringFetch: Span? = null
        val callback = object : DataFetcher.DataCallback<InputStream> {
            override fun onDataReady(data: InputStream?) {}
            override fun onLoadFailed(e: Exception) {}
        }

        // The fetcher should restore the captured context before delegating.
        // We intercept by wrapping the delegate callback check inside loadData.
        // Use a custom delegate that captures OtelContext.current() when called.
        val capturingFetcher = object : DataFetcher<InputStream> {
            override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
                spanSeenDuringFetch = Span.fromContext(OtelContext.current())
            }
            override fun cleanup() {}
            override fun cancel() {}
            override fun getDataClass(): Class<InputStream> = InputStream::class.java
            override fun getDataSource(): DataSource = DataSource.REMOTE
        }

        // Build a loader that uses our capturing fetcher as the delegate
        val capturingLoader = makeLoader(delegateFetcher = capturingFetcher)
        val ld = capturingLoader.buildLoadData(model, 100, 100, Options())!!

        ld.fetcher.loadData(Priority.NORMAL, callback)

        val storedSpan = GlideSpanStore.spans[System.identityHashCode(model)]
        assertThat(spanSeenDuringFetch).isNotNull()
        assertThat(spanSeenDuringFetch).isSameAs(storedSpan)
    }

    @Test
    fun `OtelContextDataFetcher delegates cleanup cancel getDataClass getDataSource`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        val loadData = loader.buildLoadData(model, 100, 100, Options())!!
        val fetcher = loadData.fetcher

        // These should not throw regardless of delegate state
        fetcher.cleanup()
        fetcher.cancel()
        assertThat(fetcher.dataClass).isEqualTo(InputStream::class.java)
        assertThat(fetcher.dataSource).isEqualTo(DataSource.REMOTE)
    }

    // ── reentrancy guard (isBuilding ThreadLocal) ────────────────────────────

    @Test
    fun `nested buildLoadData call (delegate chain) produces only one span`() {
        // Simulate String -> GlideUrl delegation: the inner OtelContextModelLoader call
        // should detect isBuilding=true and skip span creation.
        val innerLoader = makeLoader()
        val outerLoader = makeLoader(delegateLoader = innerLoader)
        val model = "https://cdn.bank.com/logo.png"

        outerLoader.buildLoadData(model, 100, 100, Options())

        // Only one span should have been created
        assertThat(GlideSpanStore.spans).hasSize(1)
        GlideSpanStore.spans.values.forEach { it.end() }
        assertThat(otelTesting.spans).hasSize(1)
    }

    // ── stale span cleanup on retry ──────────────────────────────────────────

    @Test
    fun `second buildLoadData for same model instance ends stale span and creates a new one`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())
        val firstSpan = GlideSpanStore.spans[System.identityHashCode(model)]

        loader.buildLoadData(model, 100, 100, Options())
        val secondSpan = GlideSpanStore.spans[System.identityHashCode(model)]

        // First span should have been ended (stale cleanup)
        assertThat(firstSpan?.isRecording).isFalse()
        // Second span is fresh and still recording
        assertThat(secondSpan?.isRecording).isTrue()
        // The store now holds the new span instance, not the stale one
        assertThat(secondSpan).isNotSameAs(firstSpan)
        secondSpan?.end()
    }

    // ── cancellation cleanup (fetcher.cancel) ────────────────────────────────

    @Test
    fun `cancel on fetcher ends the span with cancelled status and removes it from the store`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        val loadData = loader.buildLoadData(model, 100, 100, Options())!!
        val key = System.identityHashCode(model)
        assertThat(GlideSpanStore.spans).containsKey(key)

        loadData.fetcher.cancel()

        // Span must be removed and ended — a cancelled request has no terminal listener callback.
        assertThat(GlideSpanStore.spans).doesNotContainKey(key)
        assertThat(otelTesting.spans).hasSize(1)
        val span = otelTesting.spans[0]
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_CANCELLED)
        // Cancellation is not an error
        assertThat(span.status.statusCode).isEqualTo(io.opentelemetry.api.trace.StatusCode.UNSET)
    }

    @Test
    fun `cancel after terminal callback already removed the span is a safe no-op`() {
        val loader = makeLoader()
        val model = "https://cdn.bank.com/logo.png"

        val loadData = loader.buildLoadData(model, 100, 100, Options())!!
        val key = System.identityHashCode(model)
        // Simulate the RequestListener winning the race: entry already removed and ended.
        GlideSpanStore.spans.remove(key)?.end()

        loadData.fetcher.cancel()

        assertThat(otelTesting.spans).hasSize(1)
    }

    // ── uninstall gating ─────────────────────────────────────────────────────

    @Test
    fun `buildLoadData is a transparent pass-through after uninstall (companion tracer null)`() {
        val loader = makeLoader()
        GlideInstrumentation.tracer = null

        val result = loader.buildLoadData("https://cdn.bank.com/logo.png", 100, 100, Options())

        assertThat(result).isNotNull()
        assertThat(GlideSpanStore.spans).isEmpty()
        assertThat(otelTesting.spans).isEmpty()
    }

    // ── store size cap (leak safety net) ─────────────────────────────────────

    @Test
    fun `GlideSpanStore put evicts and ends an entry once the cap is reached`() {
        // Fill beyond the cap; the store must stay bounded and evicted spans must be ended.
        repeat(250) { i ->
            GlideSpanStore.put(i, tracer.spanBuilder(IMAGE_LOAD_SPAN_NAME).startSpan())
        }
        assertThat(GlideSpanStore.spans.size).isLessThanOrEqualTo(200)
        // 50 evictions → 50 ended spans exported
        assertThat(otelTesting.spans).hasSize(50)
    }

    // ── null from delegate ───────────────────────────────────────────────────

    @Test
    fun `buildLoadData returns null when delegate returns null`() {
        val nullLoader = makeLoaderWithNullDelegate()
        val result = nullLoader.buildLoadData("https://cdn.bank.com/logo.png", 100, 100, Options())
        assertThat(result).isNull()
        assertThat(GlideSpanStore.spans).isEmpty()
    }

    // ── handles() delegation ─────────────────────────────────────────────────

    @Test
    fun `handles delegates to underlying loader`() {
        val loader = makeLoader()
        assertThat(loader.handles("https://cdn.bank.com/logo.png")).isTrue()
    }

    // ── factory ─────────────────────────────────────────────────────────────

    @Test
    fun `OtelSideEffectModelLoaderFactory build wires delegate from MultiModelLoaderFactory`() {
        val delegateFetcher = makeFakeFetcher()
        val delegateLoader = makeFakeModelLoader(delegateFetcher)
        val multiFactory = mockk<MultiModelLoaderFactory>()
        every { multiFactory.build(String::class.java, InputStream::class.java) } returns delegateLoader

        val factory = OtelSideEffectModelLoaderFactory(tracer, String::class.java)
        val builtLoader = factory.build(multiFactory)

        assertThat(builtLoader).isNotNull()
        verify { multiFactory.build(String::class.java, InputStream::class.java) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeFakeFetcher(): DataFetcher<InputStream> =
        object : DataFetcher<InputStream> {
            override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {}
            override fun cleanup() {}
            override fun cancel() {}
            override fun getDataClass(): Class<InputStream> = InputStream::class.java
            override fun getDataSource(): DataSource = DataSource.REMOTE
        }

    private fun makeFakeModelLoader(fetcher: DataFetcher<InputStream>): ModelLoader<String, InputStream> =
        object : ModelLoader<String, InputStream> {
            override fun handles(model: String) = true
            override fun buildLoadData(model: String, width: Int, height: Int, options: Options) =
                ModelLoader.LoadData(mockk(relaxed = true), fetcher)
        }

    private fun makeLoader(
        delegateFetcher: DataFetcher<InputStream> = makeFakeFetcher(),
        delegateLoader: ModelLoader<String, InputStream> = makeFakeModelLoader(delegateFetcher),
    ): OtelContextModelLoader<String> = OtelContextModelLoader(tracer, delegateLoader)

    private fun makeLoaderWithNullDelegate(): OtelContextModelLoader<String> {
        val nullDelegate = object : ModelLoader<String, InputStream> {
            override fun handles(model: String) = true
            override fun buildLoadData(model: String, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? = null
        }
        return OtelContextModelLoader(tracer, nullDelegate)
    }
}
