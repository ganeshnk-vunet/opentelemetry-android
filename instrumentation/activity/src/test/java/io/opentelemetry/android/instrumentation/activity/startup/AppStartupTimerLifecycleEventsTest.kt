/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [AppStartupTimer.EVENT_APPLICATION_POST_CREATED].
 *
 * [android.app.Application.ActivityLifecycleCallbacks.onActivityPreCreated] is an API 29+
 * default method; the framework won't call it on older SDKs so tests run at sdk=29.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AppStartupTimerLifecycleEventsTest {
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
    fun `applicationPostCreated event is emitted on first onActivityPreCreated`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        val callback = timer.createLifecycleCallback()
        callback.onActivityPreCreated(mockk<Activity>(relaxed = true), null)
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val eventNames = appStart.events.map { it.name }
        assertThat(eventNames).contains(AppStartupTimer.EVENT_APPLICATION_POST_CREATED)
    }

    @Test
    fun `applicationPostCreated event is emitted only once even when onActivityPreCreated fires twice`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())

        val callback = timer.createLifecycleCallback()
        val activity = mockk<Activity>(relaxed = true)
        // Simulate a second activity (e.g. biometric/splash) firing before the first frame
        callback.onActivityPreCreated(activity, null)
        callback.onActivityPreCreated(activity, null)
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val postCreatedEvents = appStart.events.filter { it.name == AppStartupTimer.EVENT_APPLICATION_POST_CREATED }
        assertThat(postCreatedEvents).hasSize(1)
    }

    @Test
    fun `applicationPostCreated event is not emitted if onActivityPreCreated never fires`() {
        val timer = AppStartupTimer()
        timer.start(sdk.getTracer("test"), Clock.getDefault())
        timer.end()

        val appStart = exporter.finishedSpanItems.single { it.name == RumConstants.APP_START_SPAN_NAME }
        val eventNames = appStart.events.map { it.name }
        assertThat(eventNames).doesNotContain(AppStartupTimer.EVENT_APPLICATION_POST_CREATED)
    }
}
