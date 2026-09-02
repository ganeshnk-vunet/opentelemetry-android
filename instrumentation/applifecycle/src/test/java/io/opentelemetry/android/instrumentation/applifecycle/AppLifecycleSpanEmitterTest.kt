/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AppLifecycleSpanEmitterTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracer: Tracer =
        OpenTelemetrySdk
            .builder()
            .setTracerProvider(
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build(),
            ).build()
            .getTracer("test-applifecycle")
    private val emitter = AppLifecycleSpanEmitter(tracer)

    @Test
    fun `emitCreated produces a device app lifecycle span with created state`() {
        emitter.emitCreated()

        val span = exporter.finishedSpanItems.single()
        assertThat(span.name).isEqualTo("device.app.lifecycle")
        assertThat(span.attributes.get(AppLifecycleConstants.APP_STATE_KEY)).isEqualTo("created")
        assertThat(span.attributes.get(AppLifecycleConstants.ANDROID_APP_STATE_KEY)).isEqualTo("created")
    }

    @Test
    fun `emitForeground produces a device app lifecycle span with foreground state`() {
        emitter.emitForeground()

        val span = exporter.finishedSpanItems.single()
        assertThat(span.attributes.get(AppLifecycleConstants.APP_STATE_KEY)).isEqualTo("foreground")
        assertThat(span.attributes.get(AppLifecycleConstants.ANDROID_APP_STATE_KEY)).isEqualTo("foreground")
    }

    @Test
    fun `emitBackground produces a device app lifecycle span with background state`() {
        emitter.emitBackground()

        val span = exporter.finishedSpanItems.single()
        assertThat(span.attributes.get(AppLifecycleConstants.APP_STATE_KEY)).isEqualTo("background")
        assertThat(span.attributes.get(AppLifecycleConstants.ANDROID_APP_STATE_KEY)).isEqualTo("background")
    }

    @Test
    fun `each emit produces its own span, not events on a shared span`() {
        emitter.emitCreated()
        emitter.emitForeground()
        emitter.emitBackground()

        assertThat(exporter.finishedSpanItems).hasSize(3)
        assertThat(exporter.finishedSpanItems).allSatisfy { assertThat(it.events).isEmpty() }
    }

    /**
     * `ProcessLifecycleOwner` dispatches `ON_START` synchronously from `onActivityPostStarted`,
     * while the activity instrumentation is holding an `app.start`/`activity.lifecycle` span
     * current. Without `setNoParent()` the `foreground` span would nest under whichever Activity
     * happened to be starting, while `background` — delayed 700 ms onto its own looper message —
     * stayed a root, making the trace shape depend on which transition it was.
     */
    @Test
    fun `spans have no parent even when an ambient span is active`() {
        val ambient = tracer.spanBuilder("activity.lifecycle").startSpan()
        val scope = ambient.makeCurrent()
        try {
            emitter.emitCreated()
            emitter.emitForeground()
            emitter.emitBackground()
        } finally {
            scope.close()
            ambient.end()
        }

        val lifecycleSpans = exporter.finishedSpanItems.filter { it.name == "device.app.lifecycle" }
        assertThat(lifecycleSpans).hasSize(3)
        assertThat(lifecycleSpans).allSatisfy { assertThat(it.parentSpanContext.isValid).isFalse() }
    }
}
