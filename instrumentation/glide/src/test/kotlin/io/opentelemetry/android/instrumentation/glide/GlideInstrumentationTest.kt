/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class GlideInstrumentationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val otelTesting: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    private lateinit var instrumentation: GlideInstrumentation
    private lateinit var context: Application
    private lateinit var openTelemetryRum: OpenTelemetryRum

    /**
     * Distinct from [Clock.getDefault]. `isSameAs` against the default singleton would still pass
     * if install assigned `Clock.getDefault()` instead of `openTelemetryRum.clock` — which is the
     * original wall-clock domain mismatch.
     */
    private val rumClock =
        object : Clock {
            override fun now(): Long = Clock.getDefault().now()

            override fun nanoTime(): Long = Clock.getDefault().nanoTime()
        }

    @BeforeEach
    fun setUp() {
        GlideInstrumentation.tracer = null
        GlideInstrumentation.clock = null
        GlideSpanStore.spans.clear()
        instrumentation = GlideInstrumentation()
        context = mockk(relaxed = true)
        openTelemetryRum = mockk()
        every { openTelemetryRum.openTelemetry } returns otelTesting.openTelemetry
        every { openTelemetryRum.clock } returns rumClock
    }

    @AfterEach
    fun tearDown() {
        GlideInstrumentation.tracer = null
        GlideInstrumentation.clock = null
        GlideSpanStore.spans.clear()
    }

    @Test
    fun `install sets shared tracer`() {
        assertThat(GlideInstrumentation.tracer).isNull()
        instrumentation.install(context, openTelemetryRum)
        assertThat(GlideInstrumentation.tracer).isNotNull()
    }

    /**
     * Without the clock, backdated synthetic spans fall back to an implicit start — correct, but it
     * silently gives up the sub-millisecond duration the memory-cache path exists to report, and
     * nothing else in the module would notice.
     */
    @Test
    fun `install captures the sdk clock`() {
        assertThat(GlideInstrumentation.clock).isNull()
        instrumentation.install(context, openTelemetryRum)
        assertThat(GlideInstrumentation.clock).isSameAs(openTelemetryRum.clock)
    }

    @Test
    fun `uninstall clears the sdk clock`() {
        instrumentation.install(context, openTelemetryRum)
        instrumentation.uninstall(context, openTelemetryRum)
        assertThat(GlideInstrumentation.clock).isNull()
    }

    @Test
    fun `install is idempotent - second call is a no-op`() {
        instrumentation.install(context, openTelemetryRum)
        val firstTracer = GlideInstrumentation.tracer

        instrumentation.install(context, openTelemetryRum)

        assertThat(GlideInstrumentation.tracer).isSameAs(firstTracer)
    }

    @Test
    fun `uninstall clears tracer`() {
        instrumentation.install(context, openTelemetryRum)
        instrumentation.uninstall(context, openTelemetryRum)
        assertThat(GlideInstrumentation.tracer).isNull()
    }

    @Test
    fun `uninstall ends orphaned in-flight spans and clears the store`() {
        val tracer = otelTesting.openTelemetry.tracerProvider.tracerBuilder("test").build()
        val span = tracer.spanBuilder("orphan").startSpan()

        GlideSpanStore.spans[42] = span

        instrumentation.install(context, openTelemetryRum)
        instrumentation.uninstall(context, openTelemetryRum)

        assertThat(span.isRecording).isFalse()
        assertThat(GlideSpanStore.spans).isEmpty()
    }

    @Test
    fun `name returns expected instrumentation name`() {
        assertThat(instrumentation.name).isEqualTo("glide")
    }
}
