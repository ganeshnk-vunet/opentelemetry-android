/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.api.trace.Span
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
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
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        val parentContext = ActiveInteractionContext.parentContextOr(Context.current())
        if (!instrumenter.shouldStart(parentContext, chain)) {
            return chain.proceed(request)
        }

        val context = instrumenter.start(parentContext, chain)
        val injectedRequest = injectContextToRequest(request, context)
        val span = Span.fromContext(context)

        // Deferring completion only works while an EventListener is actually installed, because
        // the listener is the sole caller of the coordinator's completion entry points. When the
        // reflective wiring failed -- a minified build whose eventListenerFactory field R8 renamed
        // -- nothing would ever end these spans, so fall back to ending inline, exactly as the
        // upstream TracingInterceptor does when phase timing is switched off.
        val deferCompletion = !OkHttpSingletons.eventListenerWiringFailed
        if (deferCompletion) {
            OkHttpCallCompletionCoordinator.registerTraced(call, context, chain, span)
        }

        return try {
            context.makeCurrent().use {
                val response = chain.proceed(injectedRequest)
                if (deferCompletion) {
                    OkHttpCallCompletionCoordinator.setResponse(call, response)
                } else {
                    OkHttpCallCompletionCoordinator.endImmediately(context, chain, response, null)
                }
                response
            }
        } catch (throwable: Throwable) {
            if (deferCompletion) {
                OkHttpCallCompletionCoordinator.setError(call, throwable)
            } else {
                OkHttpCallCompletionCoordinator.endImmediately(context, chain, null, throwable)
            }
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
