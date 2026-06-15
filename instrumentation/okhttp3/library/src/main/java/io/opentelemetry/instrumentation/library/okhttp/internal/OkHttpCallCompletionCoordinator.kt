/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response

internal object OkHttpCallCompletionCoordinator {
    private data class PendingTrace(
        val context: Context,
        val chain: Interceptor.Chain,
        val span: Span,
        @Volatile var response: Response? = null,
        @Volatile var error: Throwable? = null,
    )

    private val pendingTraces = ConcurrentHashMap<Call, PendingTrace>()
    private var instrumenter: Instrumenter<Interceptor.Chain, Response>? = null
    private var spanEnricher: OkHttpTimingSpanEnricher? = null

    fun configure(
        instrumenter: Instrumenter<Interceptor.Chain, Response>,
        spanEnricher: OkHttpTimingSpanEnricher,
    ) {
        this.instrumenter = instrumenter
        this.spanEnricher = spanEnricher
    }

    fun registerTraced(
        call: Call,
        context: Context,
        chain: Interceptor.Chain,
        span: Span,
    ) {
        pendingTraces[call] = PendingTrace(context, chain, span)
    }

    fun setResponse(
        call: Call,
        response: Response,
    ) {
        pendingTraces[call]?.response = response
    }

    fun setError(
        call: Call,
        error: Throwable,
    ) {
        pendingTraces[call]?.error = error
    }

    fun onCallEnd(call: Call) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.callEndNanos = System.nanoTime()
        }
        complete(call)
    }

    fun onCallFailed(
        call: Call,
        error: IOException,
    ) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.failed = true
            state.phasesComplete = false
            state.callEndNanos = System.nanoTime()
        }
        pendingTraces[call]?.error = error
        complete(call)
    }

    fun onCanceled(call: Call) {
        OkHttpCallTimingStore.updateIfPresent(call) { state ->
            state.failed = true
            state.phasesComplete = false
        }
        pendingTraces[call]?.error = IOException("Canceled")
        complete(call)
    }

    private fun complete(call: Call) {
        val pending = pendingTraces.remove(call)
        if (pending == null) {
            OkHttpCallTimingStore.discard(call)
            return
        }

        val instrumenter = instrumenter ?: return
        val spanEnricher = spanEnricher ?: return

        spanEnricher.enrich(pending.span, call)
        val error = pending.error
        if (error != null) {
            instrumenter.end(pending.context, pending.chain, null, error)
        } else {
            instrumenter.end(pending.context, pending.chain, pending.response, null)
        }
    }

    fun clear() {
        pendingTraces.clear()
    }
}
