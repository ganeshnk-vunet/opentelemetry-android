/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.StartupTimestampProvider
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AppStartupTimerContentProviderEventsTest {
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
    fun `app_base_context event is emitted with correct timestamp`() {
        val baseContextMs = System.currentTimeMillis() - 500
        val provider = fakeProvider(attachBaseContextEpochMs = baseContextMs)

        val timer = AppStartupTimer(timestampProvider = provider)
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val event = appStart.events.firstOrNull { it.name == AppStartupTimer.EVENT_BASE_CONTEXT }

        assertThat(event).isNotNull()
        assertThat(event!!.epochNanos)
            .isEqualTo(TimeUnit.MILLISECONDS.toNanos(baseContextMs))
    }

    @Test
    fun `app_init_contentprovider event is emitted with correct timestamp`() {
        val contentProviderMs = System.currentTimeMillis() - 300
        val provider = fakeProvider(contentProviderEpochMs = contentProviderMs)

        val timer = AppStartupTimer(timestampProvider = provider)
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val event = appStart.events.firstOrNull { it.name == AppStartupTimer.EVENT_CONTENT_PROVIDER_INIT }

        assertThat(event).isNotNull()
        assertThat(event!!.epochNanos)
            .isEqualTo(TimeUnit.MILLISECONDS.toNanos(contentProviderMs))
    }

    @Test
    fun `no content provider events are emitted when timestamps are zero`() {
        val provider = fakeProvider(attachBaseContextEpochMs = 0L, contentProviderEpochMs = 0L)

        val timer = AppStartupTimer(timestampProvider = provider)
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val eventNames = appStart.events.map { it.name }

        assertThat(eventNames).doesNotContain(AppStartupTimer.EVENT_BASE_CONTEXT)
        assertThat(eventNames).doesNotContain(AppStartupTimer.EVENT_CONTENT_PROVIDER_INIT)
    }

    @Test
    fun `applicationPreCreated event has the same timestamp as app_init_contentprovider`() {
        val contentProviderMs = System.currentTimeMillis() - 300
        val provider = fakeProvider(contentProviderEpochMs = contentProviderMs)

        val timer = AppStartupTimer(timestampProvider = provider)
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val contentProviderEvent = appStart.events.first { it.name == AppStartupTimer.EVENT_CONTENT_PROVIDER_INIT }
        val preCreatedEvent = appStart.events.firstOrNull { it.name == AppStartupTimer.EVENT_APPLICATION_PRE_CREATED }

        assertThat(preCreatedEvent).isNotNull()
        assertThat(preCreatedEvent!!.epochNanos).isEqualTo(contentProviderEvent.epochNanos)
    }

    @Test
    fun `applicationPreCreated event is not emitted when contentProvider timestamp is zero`() {
        val provider = fakeProvider(contentProviderEpochMs = 0L)

        val timer = AppStartupTimer(timestampProvider = provider)
        timer.start(sdk.getTracer("test"), io.opentelemetry.sdk.common.Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val eventNames = appStart.events.map { it.name }

        assertThat(eventNames).doesNotContain(AppStartupTimer.EVENT_APPLICATION_PRE_CREATED)
    }

    private fun fakeProvider(
        attachBaseContextEpochMs: Long = 0L,
        contentProviderEpochMs: Long = 0L,
    ): StartupTimestampProvider =
        object : StartupTimestampProvider {
            override val attachBaseContextEpochMs = attachBaseContextEpochMs
            override val contentProviderEpochMs = contentProviderEpochMs
        }
}
