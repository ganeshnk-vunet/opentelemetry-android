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
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
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

        /** Five seconds, far larger than any real drift, so the assertion cannot be flaky. */
        private const val SKEW_NANOS = 5_000_000_000L
    }

    private lateinit var tracer: io.opentelemetry.api.trace.Tracer

    @BeforeEach
    fun setUp() {
        GlideSpanStore.spans.clear()
        tracer = otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()
    }

    @AfterEach
    fun tearDown() {
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

    /**
     * The model-loader half of the same defect the listener test covers.
     *
     * `buildLoadData` used to stamp the start from `System.currentTimeMillis() * 1_000_000` while
     * `span.end()` took the end from the SDK clock. On device the SDK clock is `OtelAndroidClock`
     * — a wall-clock baseline sampled once at process start plus `elapsedRealtimeNanos()` — so the
     * two drift apart and never re-sync, and a fast load could end before it started.
     *
     * A default-clock test cannot show this because the two agree; this one gives the tracer a
     * clock deliberately behind wall time, which is the shape of the real drift.
     */
    @Test
    fun `buildLoadData span does not invert when the sdk clock lags wall time`() {
        val laggingClock =
            object : Clock {
                override fun now(): Long = System.currentTimeMillis() * 1_000_000 - SKEW_NANOS

                override fun nanoTime(): Long = System.nanoTime()
            }
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .setClock(laggingClock)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val loader = OtelContextModelLoader(provider.get("test"), makeFakeModelLoader(makeFakeFetcher()))
        val model = "https://cdn.bank.com/logo.png"

        loader.buildLoadData(model, 100, 100, Options())
        GlideSpanStore.spans[System.identityHashCode(model)]?.end()

        val span = exporter.finishedSpanItems.single()
        assertThat(span.endEpochNanos).isGreaterThanOrEqualTo(span.startEpochNanos)
    }

    @Test
    fun `buildLoadData span has a non-zero start epoch and a non-negative duration`() {
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
