/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Tracks touch events inside [DialogFragment] windows.
 *
 * Dialogs render into their own [android.view.Window], distinct from the host Activity's window.
 * Because the Activity-level callback only wraps `activity.window`, taps inside a dialog (e.g. a
 * toggle in a card-management sheet) would otherwise never reach the instrumentation. Registering
 * this on the host Activity's [FragmentManager] lets us wrap each dialog window as it resumes and
 * unwrap it as it pauses.
 */
internal class DialogFragmentClickCallback(
    private val clickEventGenerator: ClickEventGenerator,
) : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentResumed(
        fm: FragmentManager,
        f: Fragment,
    ) {
        (f as? DialogFragment)?.dialog?.window?.let { clickEventGenerator.startTracking(it) }
    }

    override fun onFragmentPaused(
        fm: FragmentManager,
        f: Fragment,
    ) {
        (f as? DialogFragment)?.dialog?.window?.let { clickEventGenerator.stopTracking(it) }
    }
}
