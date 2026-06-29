/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import io.opentelemetry.android.common.RumConstants.APP_FRAMEWORK_KEY
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_VERSION
import io.opentelemetry.semconv.incubating.AndroidIncubatingAttributes.ANDROID_OS_API_LEVEL
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes.APP_INSTALLATION_ID
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MANUFACTURER
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MODEL_IDENTIFIER
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MODEL_NAME
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_DESCRIPTION
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_NAME
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_TYPE
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_VERSION
import java.util.UUID

private const val SHARED_PREF_FILE = "opentelemetry-android"
private const val DEFAULT_APP_NAME = "unknown_service:android"

private const val FRAMEWORK_FLUTTER = "flutter"
private const val FRAMEWORK_REACT_NATIVE = "react_native"
private const val FRAMEWORK_NATIVE = "native_android"

/**
 * Marker classes that, when present on the classpath, identify a cross-platform shell. Ordered by
 * priority; the first present marker wins. Falls back to [FRAMEWORK_NATIVE] when none resolve.
 */
private val FRAMEWORK_MARKERS: List<Pair<String, String>> =
    listOf(
        "io.flutter.embedding.engine.FlutterEngine" to FRAMEWORK_FLUTTER,
        "com.facebook.react.ReactRootView" to FRAMEWORK_REACT_NATIVE,
    )

object AndroidResource {
    @JvmStatic
    fun createDefault(context: Context): Resource {
        val appName = readAppName(context)
        val resourceBuilder = Resource.builder().put(SERVICE_NAME, appName)
        val appVersion = readAppVersion(context)
        appVersion?.let { resourceBuilder.put(SERVICE_VERSION, it) }

        return resourceBuilder
            .put(DEVICE_MODEL_NAME, Build.MODEL)
            .put(DEVICE_MODEL_IDENTIFIER, Build.MODEL)
            .put(DEVICE_MANUFACTURER, Build.MANUFACTURER)
            .put(OS_NAME, "Android")
            .put(ANDROID_OS_API_LEVEL, Build.VERSION.SDK_INT.toString())
            .put(OS_TYPE, "linux")
            .put(OS_VERSION, Build.VERSION.RELEASE)
            .put(OS_DESCRIPTION, oSDescription)
            .put(APP_INSTALLATION_ID, readInstallId(context))
            .put(APP_FRAMEWORK_KEY, appFramework)
            .build()
    }

    /**
     * Host app framework, resolved once per process. The classpath is fixed for the process
     * lifetime, so the marker-class probes are memoized; the agent builds the default resource more
     * than once during init, and this keeps every build after the first lookup-free.
     */
    private val appFramework: String by lazy { resolveAppFramework() }

    /**
     * Resolves the framework by probing for marker classes on the classpath, returning the first
     * match in priority order. Falls back to [FRAMEWORK_NATIVE] when no cross-platform marker resolves.
     */
    private fun resolveAppFramework(): String {
        for ((className, framework) in FRAMEWORK_MARKERS) {
            if (isClassPresent(className)) {
                return framework
            }
        }
        return FRAMEWORK_NATIVE
    }

    private fun isClassPresent(className: String): Boolean =
        try {
            Class.forName(className, false, AndroidResource::class.java.classLoader)
            true
        } catch (_: Throwable) {
            // Throwable (not Exception) on purpose: a present marker with missing transitive deps
            // surfaces as NoClassDefFoundError/LinkageError, which are Errors, not Exceptions.
            false
        }

    /**
     * Minimal resource for trace spans. Device/OS/installation attrs are exported on the first
     * cold `app.start` span only via [io.opentelemetry.android.export.SelectiveResourceSpanExporter].
     */
    @JvmStatic
    fun createMinimal(context: Context): Resource =
        Resource.builder().put(SERVICE_NAME, readAppName(context)).build()

    @SuppressLint("UseKtx")
    private fun readInstallId(context: Context): String {
        // install ID is persisted using the app.installation.id semconv as its key
        val prefs = context.getSharedPreferences(SHARED_PREF_FILE, 0)
        val installId = prefs.getString(APP_INSTALLATION_ID.key, null)

        if (installId == null) {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(APP_INSTALLATION_ID.key, id).apply()
            return id
        }
        return installId
    }

    private fun readAppName(context: Context): String =
        try {
            val ctx = context.applicationContext
            val stringId =
                ctx.applicationInfo.labelRes
            if (stringId == 0) {
                ctx.applicationInfo.nonLocalizedLabel.toString()
            } else {
                ctx.getString(stringId)
            }
        } catch (_: Exception) {
            DEFAULT_APP_NAME
        }

    private fun readAppVersion(context: Context): String? =
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (_: Exception) {
            null
        }

    private val oSDescription: String
        get() {
            val osDescriptionBuilder = StringBuilder()
            return osDescriptionBuilder
                .append("Android Version ")
                .append(Build.VERSION.RELEASE)
                .append(" (Build ")
                .append(Build.ID)
                .append(" API level ")
                .append(Build.VERSION.SDK_INT)
                .append(")")
                .toString()
        }
}
