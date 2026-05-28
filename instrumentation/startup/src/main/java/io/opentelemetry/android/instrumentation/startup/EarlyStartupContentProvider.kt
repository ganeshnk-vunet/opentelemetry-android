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
 * A no-op [ContentProvider] that records the wall-clock time after all ContentProviders have
 * finished initializing, just before [android.app.Application.onCreate] is called.
 *
 * Android initializes ContentProviders in descending [android:initOrder] order. By using
 * [Int.MIN_VALUE] (-2147483648) as the init order, this provider runs as late as possible
 * in the ContentProvider initialization sequence, giving the latest practical pre-onCreate
 * timestamp. Note that ordering among providers with equal initOrder values is not defined,
 * so another provider declaring [Int.MIN_VALUE] may run concurrently or after this one.
 *
 * The captured value is stored in [ProcessStartTimestamps.contentProviderEpochMs] and later
 * emitted as the `app.init.contentprovider` event on the AppStart span by AppStartupTimer.
 *
 * This provider is registered in the startup artifact's AndroidManifest and is automatically
 * merged into the app's manifest when the artifact is on the classpath. No manual wiring is
 * required.
 */
class EarlyStartupContentProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ProcessStartTimestamps.contentProviderEpochMs = System.currentTimeMillis()
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
