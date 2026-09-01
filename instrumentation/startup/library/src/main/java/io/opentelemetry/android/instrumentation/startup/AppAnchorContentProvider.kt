/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.startup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * A no-op [ContentProvider] that records the wall-clock time at the start of the
 * ContentProvider initialization phase.
 *
 * Android initializes ContentProviders in descending [android:initOrder] order, after
 * [android.app.Application.attachBaseContext] and before [android.app.Application.onCreate].
 * By using [Int.MAX_VALUE] as the init order, this provider runs as early as possible in
 * the ContentProvider initialization sequence. Note that ordering among providers with equal
 * initOrder values is not defined, so another provider declaring [Int.MAX_VALUE] may run
 * concurrently or first.
 *
 * The captured value is stored in [ProcessStartTimestamps.contentProvidersPhaseStartEpochMs]
 * and later emitted as the `app.start.phase.content_providers.start` event on the AppStart span.
 *
 * This provider is registered in the startup artifact's AndroidManifest and is automatically
 * merged into the app's manifest when the artifact is on the classpath. No manual wiring is
 * required.
 */
class AppAnchorContentProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ProcessStartTimestamps.contentProvidersPhaseStartEpochMs = System.currentTimeMillis()
        return true
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
