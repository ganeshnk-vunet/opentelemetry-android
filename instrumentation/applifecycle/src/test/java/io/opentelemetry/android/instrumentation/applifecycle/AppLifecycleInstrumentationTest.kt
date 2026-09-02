/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.internal.services.Services
import io.opentelemetry.android.internal.services.applifecycle.AppLifecycle
import io.opentelemetry.android.internal.services.applifecycle.ApplicationStateListener
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AppLifecycleInstrumentationTest {
    private val exporter = InMemorySpanExporter.create()
    private val appLifecycle = mockk<AppLifecycle>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var rum: OpenTelemetryRum

    @BeforeEach
    fun setUp() {
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()
        rum = mockk(relaxed = true)
        every { rum.openTelemetry } returns sdk

        val services = mockk<Services>(relaxed = true)
        every { services.appLifecycle } returns appLifecycle
        Services.set(services)
    }

    @AfterEach
    fun tearDown() {
        Services.set(null)
        exporter.reset()
    }

    @Test
    fun `install emits created once and registers a listener`() {
        AppLifecycleInstrumentation().install(context, rum)

        assertThat(exporter.finishedSpanItems).hasSize(1)
        assertThat(exporter.finishedSpanItems.single().attributes.get(AppLifecycleConstants.APP_STATE_KEY))
            .isEqualTo("created")
        verify(exactly = 1) { appLifecycle.registerListener(any()) }
    }

    /**
     * A second install must not emit another `created` span or register a second listener: the
     * instrumentation holds only one reference, so an extra registration would be orphaned and
     * would keep duplicating every transition for the life of the process.
     */
    @Test
    fun `a second install is a no-op`() {
        val instrumentation = AppLifecycleInstrumentation()
        instrumentation.install(context, rum)
        instrumentation.install(context, rum)

        assertThat(exporter.finishedSpanItems).hasSize(1)
        verify(exactly = 1) { appLifecycle.registerListener(any()) }
    }

    @Test
    fun `uninstall unregisters and allows a later reinstall`() {
        val instrumentation = AppLifecycleInstrumentation()
        instrumentation.install(context, rum)
        instrumentation.uninstall(context, rum)
        instrumentation.install(context, rum)

        verify(exactly = 1) { appLifecycle.unregisterListener(any<ApplicationStateListener>()) }
        verify(exactly = 2) { appLifecycle.registerListener(any()) }
        assertThat(exporter.finishedSpanItems).hasSize(2)
    }
}
