/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.crash

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.utils.threadIdCompat
import io.opentelemetry.android.instrumentation.common.EventAttributesExtractor
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_MESSAGE
import io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_STACKTRACE
import io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_TYPE
import io.opentelemetry.semconv.incubating.ThreadIncubatingAttributes.THREAD_ID
import io.opentelemetry.semconv.incubating.ThreadIncubatingAttributes.THREAD_NAME

internal class CrashReporter(
    additionalExtractors: List<EventAttributesExtractor<CrashDetails>>,
) {
    private val extractors: List<EventAttributesExtractor<CrashDetails>> =
        additionalExtractors.toList()
    private lateinit var crashTracer: Tracer

    /** Installs the crash reporting instrumentation.  */
    fun install(openTelemetry: OpenTelemetry) {
        crashTracer = openTelemetry.tracerProvider.get("io.opentelemetry.crash")
        val handler =
            CrashReportingExceptionHandler(
                crashProcessor = { crashDetails ->
                    processCrash(crashDetails)
                },
            )
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun processCrash(crashDetails: CrashDetails) {
        RumDiagnostics.d { "crash: captured type=${crashDetails.cause.javaClass.simpleName}" }
        val throwable = crashDetails.cause
        val thread = crashDetails.thread
        val attributesBuilder =
            Attributes
                .builder()
                .put(RumConstants.ERROR_RUNTIME_KEY, RumConstants.ERROR_RUNTIME_JVM)
                .put(THREAD_ID, thread.threadIdCompat)
                .put(THREAD_NAME, thread.name)
                .put(EXCEPTION_MESSAGE, throwable.message)
                .put(
                    EXCEPTION_STACKTRACE,
                    throwable.stackTraceToString(),
                ).put(EXCEPTION_TYPE, throwable.javaClass.name)
        for (extractor in extractors) {
            val extractedAttributes = extractor.extract(Context.current(), crashDetails)
            attributesBuilder.putAll(extractedAttributes)
        }
        crashTracer
            .spanBuilder("device.crash")
            .setAllAttributes(attributesBuilder.build())
            .startSpan()
            .end()
    }
}
