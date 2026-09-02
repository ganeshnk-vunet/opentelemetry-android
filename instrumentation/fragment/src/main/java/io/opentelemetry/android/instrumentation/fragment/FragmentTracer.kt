/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.fragment

import androidx.fragment.app.Fragment
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.instrumentation.common.ActiveSpan
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

internal class FragmentTracer(
    fragment: Fragment,
    private val tracer: Tracer,
    private val screenName: String,
    private val activeSpan: ActiveSpan,
) {
    private val fragmentName: String = fragment.javaClass.simpleName

    fun startSpanIfNoneInProgress(lifecycleEvent: String): FragmentTracer {
        if (activeSpan.spanInProgress()) {
            return this
        }
        activeSpan.startSpan { createLifecycleSpan(lifecycleEvent) }
        RumDiagnostics.d { "fragment: span start event=$lifecycleEvent fragment=$fragmentName" }
        return this
    }

    fun startFragmentCreation(): FragmentTracer {
        activeSpan.startSpan { createLifecycleSpan("Created") }
        RumDiagnostics.d { "fragment: span start event=Created fragment=$fragmentName" }
        return this
    }

    private fun createLifecycleSpan(lifecycleEvent: String): Span {
        val span =
            tracer
                .spanBuilder(RumConstants.FRAGMENT_LIFECYCLE_SPAN_NAME)
                .setAttribute(FRAGMENT_NAME_KEY, fragmentName)
                .setAttribute(RumConstants.FRAGMENT_LIFECYCLE_EVENT_KEY, lifecycleEvent)
                // Canonical ui.host.* alongside the per-host attributes above, so one cross-platform
                // query can read Activity, Fragment and the iOS hosts the same way.
                .setAttribute(RumConstants.UI_HOST_KIND_KEY, RumConstants.UI_HOST_KIND_FRAGMENT)
                .setAttribute(RumConstants.UI_HOST_NAME_KEY, fragmentName)
                .setAttribute(
                    RumConstants.UI_HOST_LIFECYCLE_EVENT_KEY,
                    RumConstants.uiHostLifecycleEventOf(lifecycleEvent),
                ).startSpan()
        // do this after the span is started, so we can override the default screen.name set by the
        // RumAttributeAppender.
        span.setAttribute(RumConstants.SCREEN_NAME_KEY, screenName)
        return span
    }

    fun endActiveSpan() {
        activeSpan.endActiveSpan()
        RumDiagnostics.d { "fragment: span end fragment=$fragmentName" }
    }

    fun addPreviousScreenAttribute(): FragmentTracer {
        activeSpan.addPreviousScreenAttribute(fragmentName)
        return this
    }

    fun addEvent(eventName: String): FragmentTracer {
        activeSpan.addEvent(eventName)
        return this
    }

    companion object {
        val FRAGMENT_NAME_KEY: AttributeKey<String?> = AttributeKey.stringKey("fragment.name")
    }
}
