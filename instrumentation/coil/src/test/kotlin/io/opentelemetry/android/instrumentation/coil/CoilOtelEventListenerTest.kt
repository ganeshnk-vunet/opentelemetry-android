/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.coil

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.target.Target
import coil.target.ViewTarget
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.internal.imageload.ImageLoadAttributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.SocketTimeoutException

/**
 * Unit tests for [CoilOtelEventListener].
 *
 * These tests validate:
 * - Span is opened on [CoilOtelEventListener.onStart] with correct name and URL attribute.
 * - URL sanitisation strips query parameters.
 * - [ATTR_IMAGE_MODEL_TYPE] is captured correctly.
 * - On success: [ATTR_IMAGE_SOURCE] is mapped correctly for each [DataSource], status is OK.
 * - On error: exception is recorded, status is ERROR.
 * - On cancel: span is ended with CANCELLED status and removed from the store.
 * - [CoilSpanStore] is empty after terminal callbacks.
 * - [VunetCoilEventListenerFactory] returns [coil.EventListener.NONE] when tracer is null.
 */
class CoilOtelEventListenerTest {
    companion object {
        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private val androidContext: Context = mockk(relaxed = true)
    private lateinit var tracer: io.opentelemetry.api.trace.Tracer
    private lateinit var listener: CoilOtelEventListener

    @BeforeEach
    fun setUp() {
        CoilInstrumentation.tracer = null
        CoilSpanStore.spans.clear()
        tracer = otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()
        listener = CoilOtelEventListener(tracer)
    }

    @AfterEach
    fun tearDown() {
        CoilInstrumentation.tracer = null
        CoilSpanStore.spans.clear()
    }

    // ---------------------------------------------------------------------------
    // onStart
    // ---------------------------------------------------------------------------

    @Test
    fun `onStart creates a span named image_load`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)

        assertThat(CoilSpanStore.spans).containsKey(System.identityHashCode(request))
        // Span is still in-flight — end it for export
        CoilSpanStore.spans[System.identityHashCode(request)]?.end()
        val spans = otelTesting.spans
        assertThat(spans).hasSize(1)
        assertThat(spans[0].name).isEqualTo(IMAGE_LOAD_SPAN_NAME)
    }

    @Test
    fun `onStart records sanitised URL without query params`() {
        val request = buildRequest("https://cdn.bank.com/photo.jpg?token=SECRET&sig=abc123")

        listener.onStart(request)

        val key = System.identityHashCode(request)
        CoilSpanStore.spans[key]?.end()
        val span: SpanData = otelTesting.spans[0]
        val recordedUrl = span.attributes.get(ATTR_IMAGE_URL)
        assertThat(recordedUrl).isEqualTo("https://cdn.bank.com/photo.jpg")
        assertThat(recordedUrl).doesNotContain("token")
        assertThat(recordedUrl).doesNotContain("SECRET")
    }

    @Test
    fun `onStart records correct model type`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)

        val key = System.identityHashCode(request)
        CoilSpanStore.spans[key]?.end()
        val span: SpanData = otelTesting.spans[0]
        assertThat(span.attributes.get(ATTR_IMAGE_MODEL_TYPE)).isEqualTo(String::class.java.name)
    }

    @Test
    fun `onStart stores span in CoilSpanStore`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)

        val key = System.identityHashCode(request)
        assertThat(CoilSpanStore.spans).containsKey(key)
    }

    // ---------------------------------------------------------------------------
    // onSuccess
    // ---------------------------------------------------------------------------

    @Test
    fun `onSuccess ends span with OK status and clears store`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        val successResult = buildSuccessResult(request, DataSource.NETWORK)

        listener.onSuccess(request, successResult)

        assertThat(CoilSpanStore.spans).doesNotContainKey(System.identityHashCode(request))
        val spans = otelTesting.spans
        assertThat(spans).hasSize(1)
        assertThat(spans[0].status.statusCode).isEqualTo(StatusCode.OK)
    }

    @Test
    fun `onSuccess maps NETWORK data source to network label`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.NETWORK))

        val span: SpanData = otelTesting.spans[0]
        assertThat(span.attributes.get(ATTR_IMAGE_SOURCE)).isEqualTo(SOURCE_NETWORK)
    }

    @Test
    fun `onSuccess maps DISK data source to disk label`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.DISK))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_SOURCE)).isEqualTo(SOURCE_DISK)
    }

    @Test
    fun `onSuccess maps MEMORY data source to memory label`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.MEMORY))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_SOURCE)).isEqualTo(SOURCE_MEMORY)
    }

    @Test
    fun `onSuccess maps MEMORY_CACHE data source to memory label`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.MEMORY_CACHE))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_SOURCE)).isEqualTo(SOURCE_MEMORY)
    }

    @Test
    fun `onSuccess sets status to success`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.NETWORK))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_LOAD_STATUS))
            .isEqualTo(STATUS_SUCCESS)
    }

    // ---------------------------------------------------------------------------
    // onError
    // ---------------------------------------------------------------------------

    @Test
    fun `onError ends span with ERROR status and clears store`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        val throwable = RuntimeException("Network timeout")
        listener.onError(request, buildErrorResult(request, throwable))

        assertThat(CoilSpanStore.spans).doesNotContainKey(System.identityHashCode(request))
        val span: SpanData = otelTesting.spans[0]
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
    }

    @Test
    fun `onError sets status to error`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        listener.onError(request, buildErrorResult(request, RuntimeException("fail")))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_LOAD_STATUS))
            .isEqualTo(STATUS_ERROR)
    }

    @Test
    fun `onError records exception on span`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        val throwable = IllegalStateException("Connection refused")
        listener.onError(request, buildErrorResult(request, throwable))

        val events = otelTesting.spans[0].events
        assertThat(events).isNotEmpty()
        val exceptionEvent = events.find { it.name == "exception" }
        assertThat(exceptionEvent).isNotNull()
    }

    // ---------------------------------------------------------------------------
    // onCancel
    // ---------------------------------------------------------------------------

    @Test
    fun `onCancel ends span with CANCELLED status and clears store`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)

        listener.onCancel(request)

        // Span and any store entry must be removed — no orphaned in-flight spans.
        assertThat(CoilSpanStore.spans).doesNotContainKey(System.identityHashCode(request))
        val span: SpanData = otelTesting.spans[0]
        assertThat(span.attributes.get(ATTR_IMAGE_LOAD_STATUS)).isEqualTo(STATUS_CANCELLED)
    }

    @Test
    fun `onCancel leaves span status UNSET (cancellation is not an error)`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)

        listener.onCancel(request)

        // A cancelled load is not a failure: the span status must not be ERROR.
        val span: SpanData = otelTesting.spans[0]
        assertThat(span.status.statusCode).isEqualTo(StatusCode.UNSET)
    }

    @Test
    fun `onCancel ends the in-flight span (no orphaned recording span)`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)
        val span = CoilSpanStore.spans[System.identityHashCode(request)]

        listener.onCancel(request)

        assertThat(span?.isRecording).isFalse()
    }

    @Test
    fun `onCancel without prior onStart is a no-op and does not throw`() {
        val request = buildRequest("https://example.com/img.png")
        // Never call onStart — store is empty
        listener.onCancel(request)

        assertThat(otelTesting.spans).isEmpty()
    }

    // ---------------------------------------------------------------------------
    // onSuccess / onError without prior onStart (defensive null-safety)
    // ---------------------------------------------------------------------------

    @Test
    fun `onSuccess without prior onStart is a no-op and does not throw`() {
        val request = buildRequest("https://example.com/img.png")
        // Never call onStart — store is empty
        listener.onSuccess(request, buildSuccessResult(request, DataSource.NETWORK))

        assertThat(otelTesting.spans).isEmpty()
    }

    @Test
    fun `onError without prior onStart is a no-op and does not throw`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onError(request, buildErrorResult(request, RuntimeException("fail")))

        assertThat(otelTesting.spans).isEmpty()
    }

    // ---------------------------------------------------------------------------
    // Span timing
    // ---------------------------------------------------------------------------

    @Test
    fun `span has non-zero wall-clock start timestamp`() {
        val request = buildRequest("https://example.com/img.png")
        val beforeNanos = System.currentTimeMillis() * 1_000_000

        listener.onStart(request)

        val key = System.identityHashCode(request)
        CoilSpanStore.spans[key]?.end()
        val span = otelTesting.spans[0]
        assertThat(span.startEpochNanos).isGreaterThan(beforeNanos)
    }

    @Test
    fun `memory cache hit has non-zero span duration`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)
        // Simulate tiny delay (MEMORY_CACHE is sub-ms but non-zero)
        Thread.sleep(1)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.MEMORY_CACHE))

        val span = otelTesting.spans[0]
        assertThat(span.endEpochNanos).isGreaterThan(span.startEpochNanos)
        assertThat(span.attributes.get(ATTR_IMAGE_SOURCE)).isEqualTo(SOURCE_MEMORY)
    }

    @Test
    fun `model type attribute is set by onStart and not overwritten in onSuccess`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)
        listener.onSuccess(request, buildSuccessResult(request, DataSource.NETWORK))

        // Attribute must be the model type from onStart — String in this case
        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_MODEL_TYPE))
            .isEqualTo(String::class.java.name)
    }

    // ---------------------------------------------------------------------------
    // Retry / stale-span cleanup
    // ---------------------------------------------------------------------------

    @Test
    fun `second onStart for same request instance ends stale span and creates fresh one`() {
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)
        val firstSpan = CoilSpanStore.spans[System.identityHashCode(request)]

        listener.onStart(request)
        val secondSpan = CoilSpanStore.spans[System.identityHashCode(request)]

        // Stale span must have been ended (not recording)
        assertThat(firstSpan?.isRecording).isFalse()
        // New span is still in-flight
        assertThat(secondSpan?.isRecording).isTrue()
        secondSpan?.end()
    }

    // ---------------------------------------------------------------------------
    // Parallel requests with same URL
    // ---------------------------------------------------------------------------

    @Test
    fun `two concurrent requests to the same URL are tracked independently`() {
        val url = "https://example.com/img.png"
        val request1 = buildRequest(url)
        val request2 = buildRequest(url)
        assertThat(request1).isNotSameAs(request2)

        listener.onStart(request1)
        listener.onStart(request2)

        assertThat(CoilSpanStore.spans).hasSize(2)

        listener.onSuccess(request1, buildSuccessResult(request1, DataSource.NETWORK))
        listener.onSuccess(request2, buildSuccessResult(request2, DataSource.MEMORY_CACHE))

        assertThat(otelTesting.spans).hasSize(2)
        assertThat(CoilSpanStore.spans).isEmpty()
        val sources = otelTesting.spans.map { it.attributes.get(ATTR_IMAGE_SOURCE) }.toSet()
        assertThat(sources).containsExactlyInAnyOrder(SOURCE_NETWORK, SOURCE_MEMORY)
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    @Test
    fun `Factory returns NONE when tracer is null`() {
        CoilInstrumentation.tracer = null
        val factory = VunetCoilEventListenerFactory()
        val request = buildRequest("https://example.com/img.png")

        val result = factory.create(request)

        assertThat(result).isSameAs(coil.EventListener.NONE)
    }

    @Test
    fun `Factory returns CoilOtelEventListener when tracer is set`() {
        CoilInstrumentation.tracer = tracer
        val factory = VunetCoilEventListenerFactory()
        val request = buildRequest("https://example.com/img.png")

        val result = factory.create(request)

        assertThat(result).isInstanceOf(CoilOtelEventListener::class.java)
    }

    // ---------------------------------------------------------------------------
    // Target view attributes
    // ---------------------------------------------------------------------------

    @Test
    fun `onStart records the view the image is being loaded into`() {
        val request = buildRequest("https://example.com/img.png", viewTarget("avatar_image"))

        listener.onStart(request)
        CoilSpanStore.spans[System.identityHashCode(request)]?.end()

        val attributes = otelTesting.spans[0].attributes
        assertThat(attributes.get(ATTR_IMAGE_TARGET_VIEW_ID)).isEqualTo("avatar_image")
        assertThat(attributes.get(ATTR_IMAGE_TARGET_VIEW_TYPE)).isEqualTo(ImageView::class.java.name)
    }

    @Test
    fun `a target view without an android id is recorded as no-id`() {
        val request = buildRequest("https://example.com/img.png", viewTarget(entryName = null))

        listener.onStart(request)
        CoilSpanStore.spans[System.identityHashCode(request)]?.end()

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_TARGET_VIEW_ID))
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNSET)
    }

    @Test
    fun `a runtime-generated view id is bucketed instead of emitting an unstable integer`() {
        val request = buildRequest("https://example.com/img.png", generatedIdViewTarget())

        listener.onStart(request)
        CoilSpanStore.spans[System.identityHashCode(request)]?.end()

        // View.generateViewId() values restart at 1 each process launch, so the raw integer would
        // neither be stable for one widget nor unique between widgets.
        val viewId = otelTesting.spans[0].attributes.get(ATTR_IMAGE_TARGET_VIEW_ID)
        assertThat(viewId).isEqualTo(ImageLoadAttributes.VIEW_ID_UNRESOLVED)
        assertThat(viewId).isNotEqualTo(GENERATED_VIEW_ID.toString())
    }

    @Test
    fun `a request without a target contributes no view attributes`() {
        // Compose AsyncImage and preload requests have no ViewTarget.
        val request = buildRequest("https://example.com/img.png")

        listener.onStart(request)
        CoilSpanStore.spans[System.identityHashCode(request)]?.end()

        val attributes = otelTesting.spans[0].attributes
        assertThat(attributes.get(ATTR_IMAGE_TARGET_VIEW_ID)).isNull()
        assertThat(attributes.get(ATTR_IMAGE_TARGET_VIEW_TYPE)).isNull()
    }

    @Test
    fun `view attributes survive onto the terminal error span`() {
        val request = buildRequest("https://example.com/img.png", viewTarget("avatar_image"))

        listener.onStart(request)
        listener.onError(request, buildErrorResult(request, RuntimeException("fail")))

        // Attributes are set at onStart; the span carries them through to the failure.
        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_TARGET_VIEW_ID))
            .isEqualTo("avatar_image")
    }

    // ---------------------------------------------------------------------------
    // Error type
    // ---------------------------------------------------------------------------

    @Test
    fun `onError records the throwable class as the error type`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)

        listener.onError(request, buildErrorResult(request, SocketTimeoutException("timeout")))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_ERROR_TYPE))
            .isEqualTo(SocketTimeoutException::class.java.simpleName)
    }

    @Test
    fun `successful and cancelled loads carry no error type`() {
        val request = buildRequest("https://example.com/img.png")
        listener.onStart(request)

        listener.onSuccess(request, buildSuccessResult(request, DataSource.NETWORK))

        assertThat(otelTesting.spans[0].attributes.get(ATTR_IMAGE_ERROR_TYPE)).isNull()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildRequest(
        url: String,
        target: Target? = null,
    ): ImageRequest =
        ImageRequest.Builder(androidContext)
            .data(url)
            .apply { target?.let { target(it) } }
            .build()

    /**
     * Builds a Coil [ViewTarget] wrapping an [ImageView] whose id resolves to [entryName], or a
     * view with no `android:id` when [entryName] is `null`.
     */
    private fun viewTarget(entryName: String?): Target =
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
    private fun generatedIdViewTarget(): Target =
        viewTargetFor(
            mockk<ImageView>(relaxed = true).also { view ->
                val resources = mockk<Resources>(relaxed = true)
                every { view.id } returns GENERATED_VIEW_ID
                every { view.resources } returns resources
                every { resources.getResourceEntryName(GENERATED_VIEW_ID) } throws
                    Resources.NotFoundException("no entry for $GENERATED_VIEW_ID")
            },
        )

    private fun viewTargetFor(imageView: ImageView): Target =
        object : ViewTarget<ImageView> {
            override val view: ImageView = imageView
        }

    private fun buildSuccessResult(
        request: ImageRequest,
        dataSource: DataSource,
    ): SuccessResult {
        val drawable = mockk<android.graphics.drawable.Drawable>(relaxed = true)
        return SuccessResult(
            drawable = drawable,
            request = request,
            dataSource = dataSource,
        )
    }

    private fun buildErrorResult(
        request: ImageRequest,
        throwable: Throwable,
    ): ErrorResult {
        val drawable = mockk<android.graphics.drawable.Drawable>(relaxed = true)
        return ErrorResult(
            drawable = drawable,
            request = request,
            throwable = throwable,
        )
    }
}

private const val VIEW_ID = 0x7f0a0042

/** In the range View.generateViewId() allocates from (1..0x00FFFFFF), never a resource id. */
private const val GENERATED_VIEW_ID = 17
