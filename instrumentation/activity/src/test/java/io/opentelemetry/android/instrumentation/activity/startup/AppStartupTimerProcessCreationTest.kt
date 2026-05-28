/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric test for [AppStartupTimer] on API 24+ (where [AppStartupTimer.EVENT_PROCESS_CREATION]
 * is emitted). Robolectric defaults to a modern SDK level, so the API-24 branch fires here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AppStartupTimerProcessCreationTest {
    private lateinit var exporter: InMemorySpanExporter
    private lateinit var sdk: OpenTelemetrySdk

    @Before
    fun setUp() {
        exporter = InMemorySpanExporter.create()
        sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()
    }

    @Test
    fun `app_process_creation event is added to AppStart span on API 24+`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val spans = exporter.finishedSpanItems
        assertThat(spans).hasSize(1)

        val appStart = spans[0]
        assertThat(appStart.name).isEqualTo(RumConstants.APP_START_SPAN_NAME)

        val eventNames = appStart.events.map { it.name }
        assertThat(eventNames).contains(AppStartupTimer.EVENT_PROCESS_CREATION)
    }

    @Test
    fun `app_process_creation event timestamp is at or before span start`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems[0]
        val event = appStart.events.first { it.name == AppStartupTimer.EVENT_PROCESS_CREATION }

        // The process was forked before (or at) the span start.
        assertThat(event.epochNanos).isLessThanOrEqualTo(appStart.startEpochNanos)
    }
}
