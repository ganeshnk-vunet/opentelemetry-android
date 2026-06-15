/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import okhttp3.Call
import okhttp3.EventListener

internal class OkHttpTimingEventListenerFactory private constructor(
    private val delegate: EventListener.Factory,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val delegateListener = delegate.create(call)
        if (!OkHttpSingletons.captureNetworkTimingPhases) {
            return delegateListener
        }
        if (delegateListener === EventListener.NONE) {
            return TIMING_LISTENER
        }
        return CompositeEventListener(delegateListener, TIMING_LISTENER)
    }

    companion object {
        private val TIMING_LISTENER = OkHttpTimingEventListener()

        @JvmStatic
        fun wrap(delegate: EventListener.Factory): EventListener.Factory {
            if (!OkHttpSingletons.captureNetworkTimingPhases) {
                return delegate
            }
            if (delegate is OkHttpTimingEventListenerFactory) {
                return delegate
            }
            return OkHttpTimingEventListenerFactory(delegate)
        }
    }
}
