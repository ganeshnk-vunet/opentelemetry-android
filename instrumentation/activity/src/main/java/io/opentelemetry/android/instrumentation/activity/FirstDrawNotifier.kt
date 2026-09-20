/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import android.view.ViewTreeObserver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [action] once, after the next frame of [activity] is committed to the screen.
 *
 * This is the same mechanism `AppStartupTimer` uses to close a cold start at its first frame,
 * pulled out so a warm start can use it too. The one-shot guard is **per call** rather than the
 * process-wide `AtomicBoolean` that machinery keeps: that flag exists so the *cold* start claims
 * TTID exactly once for the life of the process, and reusing it here would let the first start
 * consume the only opportunity any later start had.
 */
internal object FirstDrawNotifier {
    /**
     * Returns `true` if a listener was attached and [action] will run later, `false` if the
     * activity has no window to observe, in which case the caller must fall back rather than wait
     * for a callback that can never arrive.
     */
    fun onNextDraw(
        activity: Activity,
        action: () -> Unit,
    ): Boolean {
        val rootView = activity.window?.decorView ?: return false
        val observer = rootView.viewTreeObserver
        if (!observer.isAlive) {
            return false
        }
        val fired = AtomicBoolean(false)
        // Held so the listener can remove itself from outside onDraw(); removing from within the
        // callback is not safe on every API level.
        var listener: ViewTreeObserver.OnDrawListener? = null
        listener =
            ViewTreeObserver.OnDrawListener {
                // Posting defers to the next looper cycle, which is after the frame reaches
                // SurfaceFlinger -- onDraw itself runs while the frame is still being produced.
                rootView.post {
                    if (!fired.compareAndSet(false, true)) {
                        return@post
                    }
                    listener?.let { attached ->
                        if (rootView.viewTreeObserver.isAlive) {
                            rootView.viewTreeObserver.removeOnDrawListener(attached)
                        }
                    }
                    action()
                }
            }
        observer.addOnDrawListener(listener)
        return true
    }
}
