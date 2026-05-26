/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.startup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.opentelemetry.android.common.ProcessStartTimestamps

/**
 * A zero-overhead [ContentProvider] whose sole job is to stamp
 * [ProcessStartTimestamps.contentProviderEpochMs] with the current wall-clock time at the
 * earliest moment library code can execute in the app process.
 *
 * Android instantiates ContentProviders — in manifest-declaration order — after
 * [android.app.Application.attachBaseContext] but **before** [android.app.Application.onCreate].
 * Registering here gives [io.opentelemetry.android.instrumentation.activity.startup.AppStartupTimer]
 * a reliable timestamp to back-date the `app.init.contentprovider` milestone onto the
 * `AppStart` span, eliminating the silent dead-zone between process fork and the first
 * activity lifecycle callback.
 *
 * Security: declared with `android:exported="false"` and `android:multiprocess="false"` so it
 * is never visible to external processes and runs only in the primary app process.
 *
 * No content is served. All resolver operations are no-ops that return null / 0.
 */
internal class EarlyStartupContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        ProcessStartTimestamps.contentProviderEpochMs = System.currentTimeMillis()
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
