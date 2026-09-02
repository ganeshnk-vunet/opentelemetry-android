/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.mockk
import io.mockk.slot
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.resources.ResourceBuilder
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.semconv.TelemetryAttributes
import io.opentelemetry.semconv.incubating.AndroidIncubatingAttributes
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/** Deterministic stand-in for [DefaultDeviceCapacityReader], also counting how often it is read. */
private class FakeDeviceCapacityReader(
    private val totalRamBytes: Long,
    private val totalDiskBytes: Long,
) : DeviceCapacityReader {
    var ramReadCount: Int = 0
        private set
    var diskReadCount: Int = 0
        private set

    override fun readTotalRamBytes(context: Context): Long {
        ramReadCount++
        return totalRamBytes
    }

    override fun readTotalDiskBytes(): Long {
        diskReadCount++
        return totalDiskBytes
    }
}

internal class AndroidResourceTest {
    private val appName: String = "robotron"
    private val prefsName: String = "opentelemetry-android"
    private val installId: String = "install-id"
    private val totalRamBytes: Long = 4_000_000_000L
    private val totalDiskBytes: Long = 64_000_000_000L
    private val osDescription: String =
        "Android Version " +
            Build.VERSION.RELEASE +
            " (Build " +
            Build.ID +
            " API level " +
            Build.VERSION.SDK_INT +
            ")"

    @RelaxedMockK
    private lateinit var ctx: Context
    private lateinit var expectedResourceBuilder: ResourceBuilder
    private lateinit var appInfo: ApplicationInfo
    private val deviceCapacityReader = FakeDeviceCapacityReader(totalRamBytes, totalDiskBytes)

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        every { ctx.getSharedPreferences(prefsName, 0) } returns
            mockk {
                every {
                    getString(
                        AppIncubatingAttributes.APP_INSTALLATION_ID.key,
                        null,
                    )
                } returns installId
            }

        appInfo =
            ApplicationInfo().apply {
                labelRes = 12345
            }

        every { ctx.applicationContext.applicationInfo } returns appInfo
        every { ctx.applicationContext.getString(appInfo.labelRes) } returns appName

        expectedResourceBuilder =
            Resource
                .builder()
                .put(ServiceAttributes.SERVICE_NAME, appName)
                .put(DeviceIncubatingAttributes.DEVICE_MODEL_NAME, Build.MODEL)
                .put(DeviceIncubatingAttributes.DEVICE_MODEL_IDENTIFIER, Build.MODEL)
                .put(DeviceIncubatingAttributes.DEVICE_MANUFACTURER, Build.MANUFACTURER)
                .put(OsIncubatingAttributes.OS_NAME, "Android")
                .put(OsIncubatingAttributes.OS_TYPE, "linux")
                .put(OsIncubatingAttributes.OS_VERSION, Build.VERSION.RELEASE)
                .put(
                    AndroidIncubatingAttributes.ANDROID_OS_API_LEVEL,
                    Build.VERSION.SDK_INT.toString(),
                ).put(OsIncubatingAttributes.OS_DESCRIPTION, osDescription)
                .put(AppIncubatingAttributes.APP_INSTALLATION_ID, installId)
                .put(RumConstants.APP_FRAMEWORK_KEY, "native_android")
                .put(AndroidResource.SYSTEM_MEMORY_TOTAL, totalRamBytes)
                .put(AndroidResource.SYSTEM_DISK_TOTAL, totalDiskBytes)
    }

    @Test
    fun testFullResource() {
        assertResourceMatches()
        assertTelemetrySdkAttributesAbsent(AndroidResource.createDefault(ctx, deviceCapacityReader))
    }

    /**
     * Pins the emitted key strings as literals. Asserting through
     * [AndroidResource.SYSTEM_MEMORY_TOTAL] on both sides would pass even if the constant's string
     * were mistyped, which is the contract with existing `app.metrics` dashboards.
     */
    @Test
    fun `capacity attributes use the canonical wire keys`() {
        val resource = AndroidResource.createDefault(ctx, deviceCapacityReader)

        assertThat(resource.attributes.get(AttributeKey.longKey("system.memory.total")))
            .isEqualTo(totalRamBytes)
        assertThat(resource.attributes.get(AttributeKey.longKey("system.disk.total")))
            .isEqualTo(totalDiskBytes)
    }

    /**
     * The resource is immutable for the process lifetime, so an unreadable value must be omitted
     * rather than published as a sentinel that every log and metric would then carry.
     */
    @Test
    fun `unreadable capacity values are omitted rather than reported as a sentinel`() {
        val failing = FakeDeviceCapacityReader(totalRamBytes = -1L, totalDiskBytes = -1L)

        val resource = AndroidResource.createDefault(ctx, failing)

        assertThat(resource.attributes.get(AndroidResource.SYSTEM_MEMORY_TOTAL)).isNull()
        assertThat(resource.attributes.get(AndroidResource.SYSTEM_DISK_TOTAL)).isNull()
    }

    @Test
    fun `a single unreadable value does not suppress the other`() {
        val partial = FakeDeviceCapacityReader(totalRamBytes = -1L, totalDiskBytes = totalDiskBytes)

        val resource = AndroidResource.createDefault(ctx, partial)

        assertThat(resource.attributes.get(AndroidResource.SYSTEM_MEMORY_TOTAL)).isNull()
        assertThat(resource.attributes.get(AndroidResource.SYSTEM_DISK_TOTAL)).isEqualTo(totalDiskBytes)
    }

    /** Each resource build reads once; the real reader memoizes across builds (see its own test). */
    @Test
    fun `each resource build reads each capacity value once`() {
        val counting = FakeDeviceCapacityReader(totalRamBytes, totalDiskBytes)

        AndroidResource.createDefault(ctx, counting)

        assertThat(counting.ramReadCount).isEqualTo(1)
        assertThat(counting.diskReadCount).isEqualTo(1)
    }

    // The one-arg createDefault(context) is covered in DefaultDeviceCapacityReaderTest, which has a
    // real Robolectric context; the mocked Context here cannot drive the real reader.

    @Test
    fun testMinimalResource() {
        val minimal = AndroidResource.createMinimal(ctx)
        val expected =
            Resource.builder().put(ServiceAttributes.SERVICE_NAME, appName).build()
        assertEquals(expected, minimal)
        assertThat(minimal.getAttribute(DeviceIncubatingAttributes.DEVICE_MODEL_NAME)).isNull()
        assertTelemetrySdkAttributesAbsent(minimal)
    }

    @Test
    fun `resolveAppFramework detects flutter`() {
        assertEquals(
            "flutter",
            AndroidResource.resolveAppFramework { it == "io.flutter.embedding.engine.FlutterEngine" },
        )
    }

    @Test
    fun `resolveAppFramework detects react native via ReactApplication`() {
        assertEquals(
            "react_native",
            AndroidResource.resolveAppFramework { it == "com.facebook.react.ReactApplication" },
        )
    }

    @Test
    fun `resolveAppFramework detects react native via legacy ReactRootView`() {
        assertEquals(
            "react_native",
            AndroidResource.resolveAppFramework { it == "com.facebook.react.ReactRootView" },
        )
    }

    @Test
    fun `resolveAppFramework falls back to native_android when no marker present`() {
        assertEquals("native_android", AndroidResource.resolveAppFramework { false })
    }

    @Test
    fun `fall back to nonLocalizedLabel if needed`() {
        appInfo =
            ApplicationInfo().apply {
                labelRes = 0
                nonLocalizedLabel = "shim sham"
            }
        every { ctx.applicationContext.applicationInfo } returns appInfo

        assertResourceMatches(
            extraAttributes = mapOf(ServiceAttributes.SERVICE_NAME to "shim sham"),
        )
    }

    @Test
    fun testProblematicContext() {
        every { ctx.applicationContext.applicationInfo } throws SecurityException("cannot do that")
        every { ctx.applicationContext.resources } throws SecurityException("boom")

        assertResourceMatches(
            extraAttributes = mapOf(ServiceAttributes.SERVICE_NAME to "unknown_service:android"),
        )
    }

    @Test
    fun `test install id generated if none available`() {
        val slot = slot<String>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { ctx.getSharedPreferences(prefsName, 0) } returns
            mockk {
                every {
                    getString(
                        AppIncubatingAttributes.APP_INSTALLATION_ID.key,
                        null,
                    )
                } returns null
                every { edit() } returns editor
            }

        every {
            editor.putString(
                AppIncubatingAttributes.APP_INSTALLATION_ID.key,
                capture(slot),
            )
        } returns editor

        assertResourceMatches(
            resource = AndroidResource.createDefault(ctx, deviceCapacityReader),
            extraAttributes = mapOf(AppIncubatingAttributes.APP_INSTALLATION_ID to slot.captured),
        )
        assertNotNull(UUID.fromString(slot.captured))
    }

    private fun assertResourceMatches(
        resource: Resource = AndroidResource.createDefault(ctx, deviceCapacityReader),
        extraAttributes: Map<AttributeKey<*>, String> = emptyMap(),
    ) {
        extraAttributes.forEach { entry ->
            expectedResourceBuilder.put(entry.key.key, entry.value)
        }
        val expected = expectedResourceBuilder.build()
        assertEquals(expected, resource)
    }

    private fun assertTelemetrySdkAttributesAbsent(resource: Resource) {
        assertThat(resource.getAttribute(TelemetryAttributes.TELEMETRY_SDK_LANGUAGE)).isNull()
        assertThat(resource.getAttribute(TelemetryAttributes.TELEMETRY_SDK_NAME)).isNull()
        assertThat(resource.getAttribute(TelemetryAttributes.TELEMETRY_SDK_VERSION)).isNull()
    }
}
