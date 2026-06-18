/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import android.util.Log
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.interaction.ActiveInteractionHolder
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.incubator.semconv.http.HttpClientServicePeerAttributesExtractor
import io.opentelemetry.instrumentation.api.semconv.http.HttpClientRequestResendCount
import io.opentelemetry.instrumentation.api.semconv.http.HttpSpanNameExtractor
import io.opentelemetry.instrumentation.library.okhttp.OkHttpInstrumentation
import io.opentelemetry.instrumentation.okhttp.v3_0.internal.ConnectionErrorSpanInterceptor
import io.opentelemetry.instrumentation.okhttp.v3_0.internal.OkHttpAttributesGetter
import io.opentelemetry.instrumentation.okhttp.v3_0.internal.OkHttpClientInstrumenterBuilderFactory
import io.opentelemetry.instrumentation.okhttp.v3_0.internal.TracingInterceptor
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.UnaryOperator
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
object OkHttpSingletons {
    private val NOOP_INTERCEPTOR =
        Interceptor { chain: Interceptor.Chain ->
            chain.proceed(chain.request())
        }
    private val ATTRIBUTES_GETTER = OkHttpAttributesGetter()
    private val timingSpanEnricher = OkHttpTimingSpanEnricher()

    @JvmField
    var connectionErrorInterceptor: Interceptor = NOOP_INTERCEPTOR

    @JvmField
    var tracingInterceptor: Interceptor = NOOP_INTERCEPTOR

    @JvmField
    var captureNetworkTimingPhases: Boolean = true

    private val eventListenerFactoryWarningLogged = AtomicBoolean(false)

    @JvmStatic
    fun wrapEventListenerFactory(delegate: EventListener.Factory): EventListener.Factory =
        OkHttpTimingEventListenerFactory.wrap(delegate)

    /**
     * Called from woven [io.opentelemetry.instrumentation.library.okhttp.OkHttpClientAdvice].
     * Must remain public so OkHttp bytecode can invoke it after Byte Buddy weaving.
     */
    @JvmStatic
    fun applyClientInstrumentation(builder: OkHttpClient.Builder) {
        if (!builder.interceptors().contains(CALLBACK_CONTEXT_INTERCEPTOR)) {
            builder.interceptors().add(0, CALLBACK_CONTEXT_INTERCEPTOR)
            builder.interceptors().add(1, RESEND_COUNT_CONTEXT_INTERCEPTOR)
            builder.interceptors().add(2, connectionErrorInterceptor)
        }
        if (!builder.networkInterceptors().contains(tracingInterceptor)) {
            builder.addNetworkInterceptor(tracingInterceptor)
        }
        if (captureNetworkTimingPhases) {
            wrapEventListenerFactoryOnBuilder(builder)
        }
    }

    @JvmStatic
    private fun wrapEventListenerFactoryOnBuilder(builder: OkHttpClient.Builder) {
        try {
            val eventListenerFactoryField: Field =
                builder.javaClass.getDeclaredField("eventListenerFactory")
            eventListenerFactoryField.isAccessible = true
            val existingFactory = eventListenerFactoryField.get(builder) as EventListener.Factory
            val wrappedFactory = wrapEventListenerFactory(existingFactory)
            if (wrappedFactory !== existingFactory) {
                eventListenerFactoryField.set(builder, wrappedFactory)
            }
        } catch (exception: ReflectiveOperationException) {
            if (eventListenerFactoryWarningLogged.compareAndSet(false, true)) {
                Log.w(
                    RumConstants.OTEL_RUM_LOG_TAG,
                    "Failed to wire OkHttp timing EventListener factory; network phase timing disabled",
                    exception,
                )
            }
        }
    }

    fun configure(
        instrumentation: OkHttpInstrumentation,
        openTelemetry: OpenTelemetry,
    ) {
        var instrumenterBuilder =
            OkHttpClientInstrumenterBuilderFactory
                .create(openTelemetry)
                .setCapturedRequestHeaders(instrumentation.capturedRequestHeaders)
                .setCapturedResponseHeaders(instrumentation.capturedResponseHeaders)
                .setKnownMethods(instrumentation.knownMethods)
                // Note: The instrumentation allows configuring/overriding the known
                // methods, so even if the underlying extractor has them, we have
                // to pass them along here.
                .setSpanNameExtractorCustomizer(
                    UnaryOperator {
                        HttpSpanNameExtractor
                            .builder(
                                ATTRIBUTES_GETTER,
                            ).setKnownMethods(instrumentation.knownMethods)
                            .build()
                    },
                ).addAttributesExtractor(
                    HttpClientServicePeerAttributesExtractor.create(
                        ATTRIBUTES_GETTER,
                        openTelemetry,
                    ),
                ).addAttributesExtractor(
                    OkHttpErrorCategoryAttributesExtractor,
                ).setEmitExperimentalHttpClientTelemetry(
                    instrumentation.emitExperimentalHttpClientTelemetry(),
                )

        for (extractor in instrumentation.additionalExtractors) {
            instrumenterBuilder = instrumenterBuilder.addAttributesExtractor(extractor)
        }

        val instrumenter = instrumenterBuilder.build()

        connectionErrorInterceptor = ConnectionErrorSpanInterceptor(instrumenter)
        val tracing =
            if (instrumentation.captureNetworkTimingPhases()) {
                OkHttpCallCompletionCoordinator.configure(instrumenter, timingSpanEnricher)
                TimingTracingInterceptor(instrumenter, openTelemetry.propagators)
            } else {
                TracingInterceptor(instrumenter, openTelemetry.propagators)
            }
        captureNetworkTimingPhases = instrumentation.captureNetworkTimingPhases()
        tracingInterceptor =
            Interceptor { chain ->
                val request = chain.request()
                RumDiagnostics.d { "okhttp: request method=${request.method} host=${request.url.host}" }
                val response = tracing.intercept(chain)
                RumDiagnostics.d {
                    "okhttp: response code=${response.code} method=${request.method} host=${request.url.host}"
                }
                response
            }
    }

    @JvmField
    val CALLBACK_CONTEXT_INTERCEPTOR: Interceptor =
        Interceptor { chain: Interceptor.Chain ->
            val request = chain.request()
            // Prefer context explicitly propagated through an async callback; otherwise, when this
            // call runs with no active context of its own (e.g. a Retrofit `suspend` call dispatched
            // onto Dispatchers.IO without manual context propagation), fall back to the active user
            // interaction so the resulting client span still attaches to the originating click.
            val context =
                OkHttpCallbackAdviceHelper.tryRecoverPropagatedContextFromCallback(request)
                    ?: ActiveInteractionHolder.current()?.takeIf { Context.current() == Context.root() }
            context?.makeCurrent()?.use {
                return@Interceptor chain.proceed(request)
            }
            chain.proceed(request)
        }

    @JvmField
    val RESEND_COUNT_CONTEXT_INTERCEPTOR: Interceptor =
        Interceptor { chain: Interceptor.Chain ->
            HttpClientRequestResendCount.initialize(Context.current()).makeCurrent().use {
                chain.proceed(chain.request())
            }
        }
}
