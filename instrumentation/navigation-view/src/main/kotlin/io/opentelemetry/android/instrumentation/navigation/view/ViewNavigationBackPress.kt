/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

/**
 * Opt-in hook for attributing Activity/Fragment back navigations to a back press.
 *
 * The view navigation instrumentation tracks destinations automatically, but it cannot safely
 * intercept the system back button (doing so would consume the event and break predictive back).
 * Call [record] from your own back handling — e.g. inside an `OnBackPressedCallback` or before a
 * back-driven `finish()` / `popBackStack()` — so the resulting pop is reported with
 * `navigation.trigger = back_press`. Pops without a recent [record] are reported as `programmatic`.
 *
 * No-op until [ViewNavigationInstrumentation] is installed.
 */
object ViewNavigationBackPress {
    fun record() {
        ViewNavigationCollectorHolder.current()?.recordBackPress()
    }
}
