/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

/**
 * Cross-module holder for process-level timing captured before the OpenTelemetry SDK initialises.
 *
 * Fields are written exactly once by
 * [io.opentelemetry.android.instrumentation.startup.EarlyStartupContentProvider] on the main
 * thread, before [android.app.Application.onCreate] runs. They are subsequently read by
 * [io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer] when it creates the
 * `AppStart` span, allowing startup events to be back-dated to their true wall-clock origin.
 *
 * Thread safety: each field is `@Volatile`. The write happens on the main thread during
 * ContentProvider init; if anything reads from a background thread, `@Volatile` guarantees
 * visibility without requiring a lock.
 *
 * All values are 0 when the startup instrumentation is not on the classpath.
 */
object ProcessStartTimestamps {
    /**
     * `System.currentTimeMillis()` (epoch ms) recorded by
     * [io.opentelemetry.android.instrumentation.startup.AppAnchorContentProvider] — a
     * ContentProvider declared with `android:initOrder="2147483647"` so it fires before any
     * third-party library provider (Firebase, WorkManager, etc.).
     *
     * This is the earliest moment after `Application.attachBaseContext()` completed and just
     * before any library ContentProvider initialises. Used to emit the `app.base_context`
     * milestone event on the `AppStart` span.
     */
    @Volatile
    @JvmField
    var attachBaseContextEpochMs: Long = 0L

    /**
     * `System.currentTimeMillis()` (epoch ms) recorded the moment
     * [io.opentelemetry.android.instrumentation.startup.EarlyStartupContentProvider.onCreate]
     * returned. This is the earliest moment any library code can run — after the process was forked
     * and `Application.attachBaseContext()` completed, but before `Application.onCreate()`.
     *
     * Used to emit the `app.init.contentprovider` milestone event on the `AppStart` span.
     */
    @Volatile
    @JvmField
    var contentProviderEpochMs: Long = 0L
}
