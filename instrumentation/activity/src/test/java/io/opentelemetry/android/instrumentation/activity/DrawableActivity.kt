/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot

/**
 * A mocked [Activity] whose window is real enough for [FirstDrawNotifier] to register a draw
 * listener on, and whose frame can then be drawn by hand.
 *
 * A bare `mockk<Activity>()` throws on `getWindow()`, so every test that does not want the
 * no-window fallback needs this.
 */
internal class DrawableActivity {
    val activity = mockk<Activity>()
    private val decorView = mockk<View>(relaxed = true)
    private val drawListener = slot<ViewTreeObserver.OnDrawListener>()
    private val posted = slot<Runnable>()

    init {
        val window = mockk<Window>(relaxed = true)
        val observer = mockk<ViewTreeObserver>(relaxed = true)
        every { activity.window } returns window
        every { window.decorView } returns decorView
        every { decorView.viewTreeObserver } returns observer
        every { observer.isAlive } returns true
        every { observer.addOnDrawListener(capture(drawListener)) } just Runs
        every { decorView.post(capture(posted)) } returns true
    }

    /** Whether a draw listener was attached, i.e. whether a span is waiting for a frame. */
    val isAwaitingDraw: Boolean get() = drawListener.isCaptured

    /** Draws a frame, then runs the Runnable the listener posts behind it. */
    fun drawFrame() {
        drawListener.captured.onDraw()
        posted.captured.run()
    }
}
