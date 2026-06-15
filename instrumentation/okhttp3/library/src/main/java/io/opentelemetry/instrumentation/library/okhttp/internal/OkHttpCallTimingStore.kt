/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call

internal object OkHttpCallTimingStore {
    private val timings = ConcurrentHashMap<Call, CallTimingState>()

    fun stateFor(call: Call): CallTimingState = timings.computeIfAbsent(call) { CallTimingState() }

    fun remove(call: Call): OkHttpTimingResult? {
        val state = timings.remove(call) ?: return null
        return state.finalizeTiming()
    }

    fun clear() {
        timings.clear()
    }
}
