/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.ANDROID_APP_STATE_KEY
import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.APP_STATE_KEY
import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.SPAN_NAME
import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.STATE_BACKGROUND
import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.STATE_CREATED
import io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleConstants.STATE_FOREGROUND
import io.opentelemetry.api.trace.Tracer

/**
 * Emits one instantaneous `device.app.lifecycle` span per app-level state transition.
 *
 * Same shape as the existing `activity.lifecycle`/`fragment.lifecycle` emitters: start, set
 * attributes, end immediately — not a long-lived span with events.
 *
 * Spans are explicitly parentless. `ProcessLifecycleOwner` dispatches `ON_START` synchronously
 * from `onActivityPostStarted`, which lands inside the window where the activity instrumentation
 * holds an `app.start`/`activity.lifecycle` span current (opened at `onActivityPreCreated`, closed
 * at `onActivityPostResumed`). Without [io.opentelemetry.api.trace.SpanBuilder.setNoParent] the
 * `foreground` span would nest under that UI-host span while `background` — posted on
 * `ProcessLifecycleOwner`'s 700 ms delay, so it runs on its own looper message — stayed a root.
 * This is a whole-process signal; it must not hang off whichever Activity happened to be starting.
 */
internal class AppLifecycleSpanEmitter(
    private val tracer: Tracer,
) {
    fun emitCreated() = emit(STATE_CREATED)

    fun emitForeground() = emit(STATE_FOREGROUND)

    fun emitBackground() = emit(STATE_BACKGROUND)

    private fun emit(state: String) {
        tracer
            .spanBuilder(SPAN_NAME)
            .setNoParent()
            .setAttribute(APP_STATE_KEY, state)
            .setAttribute(ANDROID_APP_STATE_KEY, state)
            .startSpan()
            .end()
    }
}
