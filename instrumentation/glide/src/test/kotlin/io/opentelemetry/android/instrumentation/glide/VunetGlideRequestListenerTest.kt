
/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.StatusData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
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
    fun `onLoadFailed exception event never contains query-string tokens`() {
        val model = "https://cdn.bank.com/profile.png?auth=tokenXYZ"
        primeStore(model)
        // GlideException messages routinely embed the full request URL, tokens included.
        val exception = GlideException("Fetch failed for https://cdn.bank.com/profile.png?auth=tokenXYZ")

        listener.onLoadFailed(exception, model, mockk(relaxed = true), true)

        val span = otelTesting.spans[0]
        val exceptionEvent = span.events.first { it.name == "exception" }
        val serialized = exceptionEvent.attributes.asMap().values.joinToString()
        assertThat(serialized).doesNotContain("tokenXYZ")
        assertThat(serialized).doesNotContain("auth=")
        // The exception type must still be present for debuggability.
        assertThat(serialized).contains("GlideException")
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
    fun `synthetic span for a non-URL model records model type but no image url`() {
        GlideInstrumentation.tracer =
            otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()

        // A custom model class whose toString() embeds customer data — must never be recorded.
        class AccountAvatarModel(val accountNumber: String) {
            override fun toString(): String = "AccountAvatarModel(accountNumber=$accountNumber)"
        }
        val model = AccountAvatarModel("XX-1234-SECRET")

        listener.onResourceReady(Any(), model, null, DataSource.MEMORY_CACHE, true)

        val span = otelTesting.spans[0]
        assertThat(span.attributes[ATTR_IMAGE_URL]).isNull()
        assertThat(span.attributes[ATTR_IMAGE_MODEL_TYPE]).contains("AccountAvatarModel")

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
}
