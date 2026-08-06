/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.coil

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Unit tests for [VunetCoilInterceptor].
 *
 * Validates:
 * - When a span exists in [CoilSpanStore], the interceptor propagates the OTel context into
 *   the coroutine (i.e., the span is current during [Interceptor.Chain.proceed]).
 * - When no span exists, the interceptor is a transparent pass-through.
 * - The interceptor never throws, regardless of store state.
 */
class VunetCoilInterceptorTest {
    companion object {
        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private val androidContext: Context = mockk(relaxed = true)
    private lateinit var interceptor: VunetCoilInterceptor

    @BeforeEach
    fun setUp() {
        CoilSpanStore.spans.clear()
        interceptor = VunetCoilInterceptor()
    }

    @AfterEach
    fun tearDown() {
        CoilSpanStore.spans.clear()
    }

    @Test
    fun `intercept propagates span context when span exists in store`() =
        runBlocking {
            val tracer =
                otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()
            val span = tracer.spanBuilder("image.load").startSpan()

            val request = buildRequest("https://example.com/img.png")
            val key = System.identityHashCode(request)
            CoilSpanStore.spans[key] = span

            var capturedSpanDuringProceed: Span? = null
            val chain = mockChain(request) {
                capturedSpanDuringProceed = Span.fromContext(OtelContext.current())
                mockk<SuccessResult>(relaxed = true)
            }

            interceptor.intercept(chain)

            // The span captured inside `proceed` must be the same one we stored
            assertThat(capturedSpanDuringProceed).isSameAs(span)
            span.end()
        }

    @Test
    fun `intercept is transparent pass-through when no span in store`() =
        runBlocking<Unit> {
            val request = buildRequest("https://example.com/img.png")
            var proceedCalled = false
            val expectedResult = mockk<SuccessResult>(relaxed = true)

            val chain = mockChain(request) {
                proceedCalled = true
                expectedResult
            }

            val result = interceptor.intercept(chain)

            assertThat(proceedCalled).isTrue()
            assertThat(result).isSameAs(expectedResult)
        }

    @Test
    fun `intercept does not throw when store is empty`() =
        runBlocking<Unit> {
            val request = buildRequest("https://example.com/img.png")
            val chain = mockChain(request) { mockk<SuccessResult>(relaxed = true) }

            // Must not throw
            interceptor.intercept(chain)
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildRequest(url: String): ImageRequest =
        ImageRequest.Builder(androidContext)
            .data(url)
            .build()

    /**
     * Creates a mock [Interceptor.Chain] whose [Interceptor.Chain.proceed] invokes
     * [onProceed] — allowing tests to capture the coroutine context state during execution.
     */
    private fun mockChain(
        request: ImageRequest,
        onProceed: suspend () -> ImageResult,
    ): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request } returns request
        coEvery { chain.proceed(any()) } coAnswers { onProceed() }
        return chain
    }
}
