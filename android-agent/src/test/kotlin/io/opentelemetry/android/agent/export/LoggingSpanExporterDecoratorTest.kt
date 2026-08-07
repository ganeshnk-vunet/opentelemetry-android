/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.export

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.testing.trace.TestSpanData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoggingSpanExporterDecoratorTest {
    private val delegate: SpanExporter = mockk()
    private val endpoint = "http://127.0.0.1:4318/v1/traces"

    @Before
    fun setUp() {
        RumDiagnostics.verbose = true
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        RumDiagnostics.verbose = false
        unmockkStatic(Log::class)
    }

    @Test
    fun export_delegatesAndLogsSuccessWithEndpoint() {
        val spans = listOf(testSpan("ui.interaction"))
        val result = CompletableResultCode.ofSuccess()
        every { delegate.export(spans) } returns result

        val decorator = LoggingSpanExporterDecorator(delegate, endpoint)
        val exportResult = decorator.export(spans)

        verify { delegate.export(spans) }
        assert(exportResult.isSuccess)
        verify {
            Log.d(RumConstants.OTEL_RUM_LOG_TAG, "export spans: success count=1 endpoint=$endpoint")
        }
        verify {
            Log.d(RumConstants.OTEL_RUM_LOG_TAG, "export spans: span item=ui.interaction")
        }
    }

    @Test
    fun export_delegatesAndLogsFailureWithReason() {
        val spans = emptyList<SpanData>()
        val error = RuntimeException("connection reset")
        val result = CompletableResultCode.ofExceptionalFailure(error)
        every { delegate.export(spans) } returns result

        val decorator = LoggingSpanExporterDecorator(delegate, endpoint)
        decorator.export(spans).join(1, java.util.concurrent.TimeUnit.SECONDS)

        verify { delegate.export(spans) }
        verify {
            Log.w(
                RumConstants.OTEL_RUM_LOG_TAG,
                "export spans: failure count=0 endpoint=$endpoint reason=connection reset",
                error,
            )
        }
    }

    private fun testSpan(name: String): SpanData =
        TestSpanData
            .builder()
            .setName(name)
            .setKind(SpanKind.INTERNAL)
            .setStatus(StatusData.unset())
            .setHasEnded(true)
            .setStartEpochNanos(0)
            .setEndEpochNanos(1)
            .build()
}
