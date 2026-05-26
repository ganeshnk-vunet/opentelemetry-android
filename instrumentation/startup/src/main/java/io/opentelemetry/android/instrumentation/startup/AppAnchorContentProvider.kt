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
 * A zero-overhead [ContentProvider] declared with `android:initOrder="2147483647"` (MAX_INT)
 * so the Android OS instantiates it before any other ContentProvider in the merged manifest —
 * including Firebase, WorkManager, or any other third-party library that self-registers via
 * provider.
 *
 * It fires at the earliest possible moment after `Application.attachBaseContext()` completes,
 * which is before any library code has run. The timestamp is stored in
 * [ProcessStartTimestamps.attachBaseContextEpochMs] and later emitted as the
 * `app.base_context` milestone event on the `AppStart` span.
 *
 * Security: declared with `android:exported="false"` and `android:multiprocess="false"`.
 *
 * No content is served. All resolver operations are no-ops that return null / 0.
 */
internal class AppAnchorContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        ProcessStartTimestamps.attachBaseContextEpochMs = System.currentTimeMillis()
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
