
/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.target.Target
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.internal.imageload.ImageLoadAttributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.StatusData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class VunetGlideRequestListenerTest {
    companion object {
        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private lateinit var listener: VunetGlideRequestListener

    @BeforeEach
    fun setUp() {
        GlideSpanStore.spans.clear()
        listener = VunetGlideRequestListener()
    }

    @AfterEach
    fun tearDown() {
        GlideSpanStore.spans.clear()
        GlideInstrumentation.tracer = null
    }

    /** Simulates what OtelSideEffectModelLoader does before the request fires. */
    private fun primeStore(model: Any): Span {
        val tracer =
            otelTesting.openTelemetry
                .tracerProvider
                .tracerBuilder("test")
                .build()
        val startEpochNanos = System.currentTimeMillis() * 1_000_000
        val span =
            tracer
                .spanBuilder(IMAGE_LOAD_SPAN_NAME)
                .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
                .setAttribute(ATTR_IMAGE_URL, "https://cdn.bank.com/logo.png")
                .setAttribute(ATTR_IMAGE_MODEL_TYPE, model.javaClass.name)
                .startSpan()
        val key = System.identityHashCode(model)
        GlideSpanStore.spans[key] = span
        return span
    }

    // ── onResourceReady ──────────────────────────────────────────────────────

    @Test
    fun `onResourceReady enriches and ends span, removes entries from both maps`() {
        val model = "https://cdn.bank.com/logo.png?token=secret"
        primeStore(model)
        val key = System.identityHashCode(model)

        val result =
            listener.onResourceReady(
                resource = Any(),
                model = model,
                target = null,
                dataSource = DataSource.REMOTE,
                isFirstResource = true,
            )

        assertThat(result).isFalse()
        assertThat(GlideSpanStore.spans).doesNotContainKey(key)

        val spans = otelTesting.spans
        assertThat(spans).hasSize(1)
        val span = spans[0]
        assertThat(span.name).isEqualTo(IMAGE_LOAD_SPAN_NAME)
        assertThat(span.attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_NETWORK)
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_SUCCESS)
        assertThat(span.attributes[ATTR_IMAGE_IS_FIRST_RESOURCE]).isTrue()
        assertThat(span.status).isEqualTo(StatusData.ok())
    }

    @Test
    fun `onResourceReady maps MEMORY_CACHE to memory`() {
        val model = "https://example.com/img.jpg"
        primeStore(model)
        listener.onResourceReady(Any(), model, null, DataSource.MEMORY_CACHE, false)
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_MEMORY)
    }

    @Test
    fun `onResourceReady maps LOCAL to disk`() {
        val model = "file:///sdcard/image.jpg"
        primeStore(model)
        listener.onResourceReady(Any(), model, null, DataSource.LOCAL, false)
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_DISK)
    }

    @Test
    fun `onResourceReady maps DATA_DISK_CACHE to disk_cache`() {
        val model = "https://example.com/img.jpg"
        primeStore(model)
        listener.onResourceReady(Any(), model, null, DataSource.DATA_DISK_CACHE, false)
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_DISK_CACHE)
    }

    @Test
    fun `onResourceReady maps RESOURCE_DISK_CACHE to disk_cache`() {
        val model = "https://example.com/img.jpg"
        primeStore(model)
        listener.onResourceReady(Any(), model, null, DataSource.RESOURCE_DISK_CACHE, false)
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_DISK_CACHE)
    }

    @Test
    fun `onResourceReady with REMOTE dataSource records network source`() {
        val model = "https://example.com/img.jpg"
        primeStore(model)
        listener.onResourceReady(Any(), model, null, DataSource.REMOTE, false)
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_NETWORK)
    }

    // ── onLoadFailed ─────────────────────────────────────────────────────────

    @Test
    fun `onLoadFailed records exception and sets error status, cleans up maps`() {
        val model = "https://cdn.bank.com/profile.png?auth=tokenXYZ"
        primeStore(model)
        val key = System.identityHashCode(model)
        val exception = GlideException("Network timeout")

        val result =
            listener.onLoadFailed(
                e = exception,
                model = model,
                target = mockk(relaxed = true),
                isFirstResource = true,
            )

        assertThat(result).isFalse()
        assertThat(GlideSpanStore.spans).doesNotContainKey(key)

        val span = otelTesting.spans[0]
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_ERROR)
        assertThat(span.attributes[ATTR_IMAGE_IS_FIRST_RESOURCE]).isTrue()
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.events).anyMatch { it.name == "exception" }
    }

    @Test
    fun `onLoadFailed with null exception does not throw`() {
        val model = "https://example.com/img.png"
        primeStore(model)
        val result = listener.onLoadFailed(null, model, mockk(relaxed = true), false)
        assertThat(result).isFalse()
        assertThat(otelTesting.spans).hasSize(1)
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `onResourceReady with no primed span and REMOTE source is a graceful no-op`() {
        // GlideSpanStore is empty — simulates ModelLoader being skipped or disabled
        val result =
            listener.onResourceReady(Any(), "https://example.com/img.jpg", null, DataSource.REMOTE, false)
        assertThat(result).isFalse()
        assertThat(otelTesting.spans).isEmpty()
    }

    // ── memory-cache synthetic span ──────────────────────────────────────────

    @Test
    fun `onResourceReady with MEMORY_CACHE and no primed span creates synthetic span`() {
        // Set up tracer so the synthetic span path can fire
        GlideInstrumentation.tracer =
            otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()

        val model = "https://cdn.bank.com/logo.png?token=SECRET"
        val result =
            listener.onResourceReady(Any(), model, null, DataSource.MEMORY_CACHE, true)

        assertThat(result).isFalse()
        val spans = otelTesting.spans
        assertThat(spans).hasSize(1)
        val span = spans[0]
        assertThat(span.name).isEqualTo(IMAGE_LOAD_SPAN_NAME)
        assertThat(span.attributes[ATTR_IMAGE_SOURCE]).isEqualTo(SOURCE_MEMORY)
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_SUCCESS)
        assertThat(span.attributes[ATTR_IMAGE_IS_FIRST_RESOURCE]).isTrue()
        // URL sanitisation must strip the query parameter
        assertThat(span.attributes[ATTR_IMAGE_URL]).isEqualTo("https://cdn.bank.com/logo.png")
        assertThat(span.attributes[ATTR_IMAGE_URL]).doesNotContain("SECRET")

        GlideInstrumentation.tracer = null
    }

    @Test
    fun `onResourceReady with MEMORY_CACHE and null tracer is a no-op`() {
        GlideInstrumentation.tracer = null
        val result =
            listener.onResourceReady(Any(), "https://example.com/img.jpg", null, DataSource.MEMORY_CACHE, false)
        assertThat(result).isFalse()
        assertThat(otelTesting.spans).isEmpty()
    }

    // ── onLoadFailed synthetic span fallback ─────────────────────────────────

    @Test
    fun `onLoadFailed with no primed span creates synthetic error span`() {
        GlideInstrumentation.tracer =
            otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()

        val model = "https://cdn.bank.com/profile.png?auth=tokenXYZ"
        val exception = GlideException("Connection refused")
        val result = listener.onLoadFailed(exception, model, mockk(relaxed = true), true)

        assertThat(result).isFalse()
        val spans = otelTesting.spans
        assertThat(spans).hasSize(1)
        val span = spans[0]
        assertThat(span.name).isEqualTo(IMAGE_LOAD_SPAN_NAME)
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_ERROR)
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.events).anyMatch { it.name == "exception" }
        // URL sanitisation
        assertThat(span.attributes[ATTR_IMAGE_URL]).isEqualTo("https://cdn.bank.com/profile.png")

        GlideInstrumentation.tracer = null
    }

    @Test
    fun `parallel requests with same URL but different model instances tracked independently`() {
        val tracer =
            otelTesting.openTelemetry
                .tracerProvider
                .tracerBuilder("test")
                .build()

        // Two different String instances with the same content
        val model1 = String("https://cdn.bank.com/image.png".toCharArray())
        val model2 = String("https://cdn.bank.com/image.png".toCharArray())
        assertThat(model1).isEqualTo(model2) // same content
        assertThat(model1).isNotSameAs(model2) // different instances

        listOf(model1, model2).forEach { m ->
            val span =
                tracer.spanBuilder(IMAGE_LOAD_SPAN_NAME)
                    .startSpan()
            GlideSpanStore.spans[System.identityHashCode(m)] = span
        }

        listener.onResourceReady(Any(), model1, null, DataSource.REMOTE, true)
        listener.onResourceReady(Any(), model2, null, DataSource.MEMORY_CACHE, false)

        // Both spans ended independently
        assertThat(otelTesting.spans).hasSize(2)
        assertThat(GlideSpanStore.spans).isEmpty()
    }

    // ── target view attributes ───────────────────────────────────────────────

    @Test
    fun `onResourceReady records the view the image was loaded into`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        listener.onResourceReady(Any(), model, viewTarget("avatar_image"), DataSource.REMOTE, true)

        val attributes = otelTesting.spans[0].attributes
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_ID]).isEqualTo("avatar_image")
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_TYPE]).isEqualTo(ImageView::class.java.name)
    }

    @Test
    fun `onLoadFailed records the view the image failed to load into`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        listener.onLoadFailed(GlideException("boom"), model, viewTarget("avatar_image"), true)

        val attributes = otelTesting.spans[0].attributes
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_ID]).isEqualTo("avatar_image")
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_TYPE]).isEqualTo(ImageView::class.java.name)
    }

    @Test
    fun `a target view without an android id is recorded as no-id`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        listener.onResourceReady(Any(), model, viewTarget(entryName = null), DataSource.REMOTE, true)

        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_TARGET_VIEW_ID])
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNSET)
    }

    @Test
    fun `a runtime-generated view id is bucketed instead of emitting an unstable integer`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        listener.onResourceReady(Any(), model, generatedIdViewTarget(), DataSource.REMOTE, true)

        // View.generateViewId() values restart at 1 each process launch, so the raw integer would
        // neither be stable for one widget nor unique between widgets.
        val viewId = otelTesting.spans[0].attributes[ATTR_IMAGE_TARGET_VIEW_ID]
        assertThat(viewId).isEqualTo(ImageLoadAttributes.VIEW_ID_UNRESOLVED)
        assertThat(viewId).isNotEqualTo(GENERATED_VIEW_ID.toString())
    }

    @Test
    fun `a non-view target contributes no view attributes`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        // submit() / preload() hand back plain Targets that are not backed by a View.
        listener.onResourceReady(Any(), model, mockk(relaxed = true), DataSource.REMOTE, true)

        val attributes = otelTesting.spans[0].attributes
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_ID]).isNull()
        assertThat(attributes[ATTR_IMAGE_TARGET_VIEW_TYPE]).isNull()
    }

    // ── error type ───────────────────────────────────────────────────────────

    @Test
    fun `onLoadFailed records the root cause as the error type`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)
        val glideException = GlideException("Failed to load resource", SocketTimeoutException("timeout"))

        listener.onLoadFailed(glideException, model, mockk(relaxed = true), false)

        // The wrapping GlideException is generic — the root cause is what makes failures groupable.
        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_ERROR_TYPE])
            .isEqualTo(SocketTimeoutException::class.java.simpleName)
    }

    @Test
    fun `onLoadFailed falls back to the GlideException when it has no root causes`() {
        val model = "https://cdn.bank.com/logo.png"
        primeStore(model)

        listener.onLoadFailed(GlideException("Failed to load resource"), model, mockk(relaxed = true), false)

        assertThat(otelTesting.spans[0].attributes[ATTR_IMAGE_ERROR_TYPE])
            .isEqualTo(GlideException::class.java.simpleName)
    }

    // ── null model ───────────────────────────────────────────────────────────

    @Test
    fun `onLoadFailed with a null model still reports the failure`() {
        GlideInstrumentation.tracer =
            otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()

        // Glide.with(view).load(null) fails with a null model — previously dropped entirely.
        val result = listener.onLoadFailed(GlideException("boom"), null, mockk(relaxed = true), true)

        assertThat(result).isFalse()
        val span = otelTesting.spans.single()
        assertThat(span.attributes[ATTR_IMAGE_LOAD_STATUS]).isEqualTo(STATUS_ERROR)
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.attributes[ATTR_IMAGE_URL]).isEqualTo(VALUE_UNKNOWN)
        assertThat(span.attributes[ATTR_IMAGE_MODEL_TYPE]).isEqualTo(VALUE_UNKNOWN)

        GlideInstrumentation.tracer = null
    }

    @Test
    fun `onLoadFailed with a null model and no tracer is a graceful no-op`() {
        GlideInstrumentation.tracer = null

        val result = listener.onLoadFailed(GlideException("boom"), null, mockk(relaxed = true), true)

        assertThat(result).isFalse()
        assertThat(otelTesting.spans).isEmpty()
    }

    /**
     * Builds a Glide [ViewTarget] wrapping an [ImageView] whose id resolves to [entryName], or a
     * view with no `android:id` when [entryName] is `null`.
     */
    private fun viewTarget(entryName: String?): Target<Any> =
        viewTargetFor(
            mockk<ImageView>(relaxed = true).also { view ->
                if (entryName == null) {
                    every { view.id } returns View.NO_ID
                } else {
                    val resources = mockk<Resources>(relaxed = true)
                    every { view.id } returns VIEW_ID
                    every { view.resources } returns resources
                    every { resources.getResourceEntryName(VIEW_ID) } returns entryName
                }
            },
        )

    /**
     * Builds a target whose view carries a runtime id from `View.generateViewId()`: the id is set,
     * but the resource table has no entry for it, so lookup throws.
     */
    private fun generatedIdViewTarget(): Target<Any> =
        viewTargetFor(
            mockk<ImageView>(relaxed = true).also { view ->
                val resources = mockk<Resources>(relaxed = true)
                every { view.id } returns GENERATED_VIEW_ID
                every { view.resources } returns resources
                every { resources.getResourceEntryName(GENERATED_VIEW_ID) } throws
                    Resources.NotFoundException("no entry for $GENERATED_VIEW_ID")
            },
        )

    @Suppress("DEPRECATION") // ViewTarget is deprecated in Glide but still backs into(ImageView).
    private fun viewTargetFor(view: ImageView): Target<Any> {
        val target =
            mockk<com.bumptech.glide.request.target.ViewTarget<ImageView, Any>>(relaxed = true)
        every { target.view } returns view
        @Suppress("UNCHECKED_CAST")
        return target as Target<Any>
    }
}

private const val VIEW_ID = 0x7f0a0042

/** In the range View.generateViewId() allocates from (1..0x00FFFFFF), never a resource id. */
private const val GENERATED_VIEW_ID = 17
