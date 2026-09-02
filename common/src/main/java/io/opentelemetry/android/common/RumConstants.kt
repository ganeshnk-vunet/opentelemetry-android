/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

import io.opentelemetry.api.common.AttributeKey
import java.util.concurrent.TimeUnit

object RumConstants {
    const val OTEL_RUM_LOG_TAG: String = "VuNetRUM"

    @JvmField
    val LAST_SCREEN_NAME_KEY: AttributeKey<String> = AttributeKey.stringKey("last.screen.name")

    @JvmField
    val SCREEN_NAME_KEY: AttributeKey<String> = AttributeKey.stringKey("screen.name")

    /**
     * Canonical app-start type (cold / warm / hot). Supersedes the legacy `start.type` wire key;
     * the constant name is unchanged so this stays binary-compatible for existing callers.
     */
    @JvmField
    val START_TYPE_KEY: AttributeKey<String> = AttributeKey.stringKey("app.start.type")

    /** App framework the host application is built with (e.g. native_android, flutter, react_native). */
    @JvmField
    val APP_FRAMEWORK_KEY: AttributeKey<String> = AttributeKey.stringKey("app.framework")

    @JvmField
    val STORAGE_SPACE_FREE_KEY: AttributeKey<Long> = AttributeKey.longKey("storage.free")

    @JvmField
    val HEAP_FREE_KEY: AttributeKey<Long> = AttributeKey.longKey("heap.free")

    @JvmField
    val BATTERY_PERCENT_KEY: AttributeKey<Double> = AttributeKey.doubleKey("battery.percent")

    /**
     * Language runtime that produced a fault, set on `device.crash` and `device.anr`.
     *
     * This SDK always emits [ERROR_RUNTIME_JVM]. Anything that goes through the crash or ANR
     * reporter — including a Dart `FlutterError` or React Native exception rethrown into the
     * Android uncaught handler — is `jvm` by definition. Grouping by runtime only works if
     * wrappers emit their own `device.crash` / `device.anr`, or overwrite this attribute via
     * `addAttributesExtractor`, using [ERROR_RUNTIME_DART] or [ERROR_RUNTIME_JS].
     *
     * The value space is documented here as [ERROR_RUNTIME_JVM], [ERROR_RUNTIME_DART],
     * [ERROR_RUNTIME_JS] so wrappers that emit their own spans can copy the same lowercase
     * runtime names (`dart` vs `Dart` vs `dartvm` would make the attribute ungroupable).
     * Wrappers typically copy these string values rather than importing this class. Values
     * name the *runtime*, not the UI framework; the framework is reported separately by
     * [APP_FRAMEWORK_KEY].
     *
     * Note this key is a deliberate extension: OpenTelemetry semantic conventions own the `error.*`
     * namespace but define only `error.type`, so `error.runtime` is non-standard by choice. If OTel
     * later defines it with different semantics, this becomes a conflict to resolve.
     */
    @JvmField
    val ERROR_RUNTIME_KEY: AttributeKey<String> = AttributeKey.stringKey("error.runtime")

    /** Value of [ERROR_RUNTIME_KEY] for faults raised by the Android JVM runtime. */
    const val ERROR_RUNTIME_JVM: String = "jvm"

    /**
     * Value of [ERROR_RUNTIME_KEY] for faults raised by the Dart runtime (Flutter).
     * Unused by this SDK; for wrappers that emit their own `device.crash` / `device.anr`.
     */
    const val ERROR_RUNTIME_DART: String = "dart"

    /**
     * Value of [ERROR_RUNTIME_KEY] for faults raised by a JavaScript runtime (React Native).
     * Unused by this SDK; for wrappers that emit their own `device.crash` / `device.anr`.
     */
    const val ERROR_RUNTIME_JS: String = "js"

    const val APP_START_SPAN_NAME: String = "app.start"

    /**
     * Maximum time from process start to UI init (and the longest an in-flight
     * `app.start` span is trusted). Longer usually means a background start,
     * whose measured duration is misleading.
     */
    @JvmField
    val APP_START_MAX_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(1)

    const val ACTIVITY_LIFECYCLE_SPAN_NAME: String = "activity.lifecycle"

    @JvmField
    val ACTIVITY_LIFECYCLE_EVENT_KEY: AttributeKey<String> =
        AttributeKey.stringKey("activity.lifecycle.event")

    const val FRAGMENT_LIFECYCLE_SPAN_NAME: String = "fragment.lifecycle"

    const val UI_INTERACTION_SPAN_NAME: String = "ui.interaction"

    @JvmField
    val FRAGMENT_LIFECYCLE_EVENT_KEY: AttributeKey<String> =
        AttributeKey.stringKey("fragment.lifecycle.event")

    /**
     * Which kind of UI host a lifecycle span describes — see the `UI_HOST_KIND_*` values.
     *
     * Canonical models Activity, Fragment and the iOS hosts as one `ui.host.*` shape so a single
     * query can span platforms. Android keeps emitting the separate
     * [ACTIVITY_LIFECYCLE_SPAN_NAME] / [FRAGMENT_LIFECYCLE_SPAN_NAME] span names; this attribute is
     * what lets a consumer filter by host kind without knowing which span name carries it.
     */
    @JvmField
    val UI_HOST_KIND_KEY: AttributeKey<String> = AttributeKey.stringKey("ui.host.kind")

    /**
     * Canonical successor to the per-host `activity.name` / `fragment.name` attributes, carrying the
     * identical value under one name. All are emitted so existing queries keep working.
     */
    @JvmField
    val UI_HOST_NAME_KEY: AttributeKey<String> = AttributeKey.stringKey("ui.host.name")

    /**
     * Canonical successor to [ACTIVITY_LIFECYCLE_EVENT_KEY] / [FRAGMENT_LIFECYCLE_EVENT_KEY].
     *
     * Unlike [UI_HOST_NAME_KEY], the value is **not** identical: canonical requires snake_case
     * (`view_destroyed`), while the superseded keys keep the PascalCase Android callback names
     * (`ViewDestroyed`). Normalising the casing here is the point — iOS already emits snake_case, so
     * a cross-platform query would otherwise have to fold case per platform. Use
     * [uiHostLifecycleEventOf] to derive the value.
     */
    @JvmField
    val UI_HOST_LIFECYCLE_EVENT_KEY: AttributeKey<String> =
        AttributeKey.stringKey("ui.host.lifecycle.event")

    const val UI_HOST_KIND_ACTIVITY: String = "activity"

    const val UI_HOST_KIND_FRAGMENT: String = "fragment"

    /**
     * Converts an Android callback-style lifecycle event to the canonical snake_case form written to
     * [UI_HOST_LIFECYCLE_EVENT_KEY] — `Resumed` to `resumed`, `ViewDestroyed` to `view_destroyed`.
     *
     * A transformation rather than a lookup table because the emitters pass an open `String`, so a
     * fixed table would need a fallback anyway. Input that is already lowercase passes through
     * unchanged.
     */
    @JvmStatic
    fun uiHostLifecycleEventOf(lifecycleEvent: String): String =
        lifecycleEvent.replace(PASCAL_CASE_BOUNDARY, "$1_$2").lowercase()

    /**
     * Human-readable summary of what a span represents, derived by `ActionSummarySpanExporter`
     * (e.g. `App cold start`, `Clicked button 'Pay'`).
     *
     * Emits the canonical `semantic.summary`. The constant identifier is deliberately left as
     * `APP_ACTION_SUMMARY_KEY` so this stays source- and binary-compatible for existing callers —
     * only the emitted wire key changes.
     */
    @JvmField
    val APP_ACTION_SUMMARY_KEY: AttributeKey<String> =
        AttributeKey.stringKey("semantic.summary")

    /**
     * Matches a lowercase-to-uppercase boundary, the only word break in the Android callback names.
     * Precompiled because [uiHostLifecycleEventOf] runs on every lifecycle span.
     */
    private val PASCAL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")

    object Events {
        const val INIT_EVENT_STARTED: String = "rum.sdk.init.started"
        const val INIT_EVENT_CONFIG: String = "rum.sdk.init.config"
        const val INIT_EVENT_NET_PROVIDER: String = "rum.sdk.init.net.provider"
        const val INIT_EVENT_NET_MONITOR: String = "rum.sdk.init.net.monitor"
        const val INIT_EVENT_ANR_MONITOR: String = "rum.sdk.init.anr_monitor"
        const val INIT_EVENT_JANK_MONITOR: String = "rum.sdk.init.jank_monitor"
        const val INIT_EVENT_CRASH_REPORTER: String = "rum.sdk.init.crash.reporter"
        const val INIT_EVENT_SPAN_EXPORTER: String = "rum.sdk.init.span.exporter"

        // TODO: Use the semconv when available
        const val EVENT_SESSION_START: String = "session.start"
        const val EVENT_SESSION_END: String = "session.end"
    }
}
