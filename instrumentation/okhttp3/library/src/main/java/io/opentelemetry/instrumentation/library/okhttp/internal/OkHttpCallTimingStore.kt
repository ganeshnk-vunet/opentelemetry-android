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

    fun updateIfPresent(
        call: Call,
        block: (CallTimingState) -> Unit,
    ) {
        timings[call]?.let(block)
    }

    fun discard(call: Call) {
        timings.remove(call)
    }

    fun remove(call: Call): OkHttpTimingResult? {
        val state = timings.remove(call) ?: return null
        return state.finalizeTiming()
    }

    /**
     * Drops entries created before [cutoffNanos] for which [isEligible] holds. Used by the
     * completion watchdog to reclaim state belonging to calls that never started a span, which
     * nothing else would ever remove.
     */
    fun discardOlderThan(
        cutoffNanos: Long,
        isEligible: (Call) -> Boolean,
    ) {
        // An explicit iterator rather than Collection.removeIf, which is unavailable below API 24
        // and this module's minSdk is 23.
        val iterator = timings.entries.iterator()
        while (iterator.hasNext()) {
            val (call, state) = iterator.next()
            if (cutoffNanos - state.createdAtNanos >= 0 &&
                !state.hasOpenNetworkPhase() &&
                isEligible(call)
            ) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        timings.clear()
    }
}
