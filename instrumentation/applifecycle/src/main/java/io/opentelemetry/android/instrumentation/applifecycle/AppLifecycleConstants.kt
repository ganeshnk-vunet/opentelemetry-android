/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import io.opentelemetry.api.common.AttributeKey

/**
 * Wire keys and value vocabulary for the `device.app.lifecycle` span.
 *
 * Canonical requires one span per app-level state transition, carrying the identical value on two
 * attributes: [APP_STATE_KEY] (the cross-platform key) and [ANDROID_APP_STATE_KEY] (the OTel
 * Android wire key). This is a process-level signal — distinct from `app.start` (startup timing)
 * and from `activity.lifecycle`/`fragment.lifecycle` (per-host spans); it must not be aliased to
 * either.
 */
internal object AppLifecycleConstants {
    const val SPAN_NAME: String = "device.app.lifecycle"

    @JvmField
    val APP_STATE_KEY: AttributeKey<String> = AttributeKey.stringKey("app.state")

    @JvmField
    val ANDROID_APP_STATE_KEY: AttributeKey<String> = AttributeKey.stringKey("android.app.state")

    /** Emitted once, before any real transition — Android-only, no iOS equivalent. */
    const val STATE_CREATED: String = "created"
    const val STATE_FOREGROUND: String = "foreground"
    const val STATE_BACKGROUND: String = "background"
}
