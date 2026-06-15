/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal class TimingTracingInterceptor(
    private val instrumenter: Instrumenter<Interceptor.Chain, Response>,
    private val propagators: ContextPropagators,
    private val spanEnricher: OkHttpTimingSpanEnricher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val parentContext = Context.current()
        if (!instrumenter.shouldStart(parentContext, chain)) {
            return chain.proceed(request)
        }

        val context = instrumenter.start(parentContext, chain)
        val injectedRequest = injectContextToRequest(request, context)
        val span = io.opentelemetry.api.trace.Span.fromContext(context)
        val call = chain.call()

        try {
            context.makeCurrent().use {
                val response = chain.proceed(injectedRequest)
                spanEnricher.enrich(span, call)
                instrumenter.end(context, chain, response, null)
                return response
            }
        } catch (throwable: Throwable) {
            spanEnricher.enrich(span, call)
            instrumenter.end(context, chain, null, throwable)
            throw throwable
        }
    }

    private fun injectContextToRequest(
        request: Request,
        context: Context,
    ): Request {
        val requestBuilder = request.newBuilder()
        propagators.textMapPropagator.inject(context, requestBuilder, OkHttpRequestHeaderSetter)
        return requestBuilder.build()
    }

    private object OkHttpRequestHeaderSetter : TextMapSetter<Request.Builder> {
        override fun set(
            carrier: Request.Builder?,
            key: String,
            value: String,
        ) {
            carrier?.header(key, value)
        }
    }
}
