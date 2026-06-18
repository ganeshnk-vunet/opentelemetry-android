/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.os.Build.VERSION_CODES
import android.view.ActionMode
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.Window
import android.view.Window.Callback
import androidx.annotation.RequiresApi
import io.opentelemetry.android.common.RumDiagnostics

internal class WindowCallbackWrapper(
    private val callback: Callback,
    private val window: Window,
    private val clickEventGenerator: ClickEventGenerator,
) : Callback by callback {
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        // Instrumentation must never affect the host app: any failure while deriving telemetry is
        // swallowed so the touch is always delivered to the real callback. Critical for the apps
        // this ships into (e.g. banking), where a dropped touch or crash is unacceptable.
        try {
            clickEventGenerator.generateClick(window, event)
        } catch (throwable: Throwable) {
            RumDiagnostics.d { "hybridClick: swallowed error during click handling: ${throwable.message}" }
        }
        return callback.dispatchTouchEvent(event)
    }

    @RequiresApi(api = VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        callback.onPointerCaptureChanged(hasCapture)
    }

    @RequiresApi(api = VERSION_CODES.N)
    override fun onProvideKeyboardShortcuts(
        data: List<KeyboardShortcutGroup?>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        callback.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = callback.onSearchRequested(searchEvent)

    override fun onWindowStartingActionMode(
        callback: ActionMode.Callback?,
        type: Int,
    ): ActionMode? = this.callback.onWindowStartingActionMode(callback, type)

    fun unwrap(): Callback = callback
}
