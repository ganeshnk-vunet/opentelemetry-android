/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the real reader against the Android APIs, which the resource tests deliberately stub out.
 *
 * The memoization assertions matter beyond performance: [AndroidResource.createDefault] runs twice
 * per SDK init (a field initializer in `OpenTelemetryRumBuilder` and again in
 * `OpenTelemetryRumInitializer`), usually on the main thread, and `StatFs` is a filesystem read
 * that can trip `StrictMode.detectDiskReads`.
 */
@RunWith(AndroidJUnit4::class)
class DefaultDeviceCapacityReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetCache() {
        DefaultDeviceCapacityReader.resetForTesting()
    }

    @Test
    fun `reads a plausible total ram`() {
        assertThat(DefaultDeviceCapacityReader.readTotalRamBytes(context)).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `reads a plausible total disk`() {
        assertThat(DefaultDeviceCapacityReader.readTotalDiskBytes()).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `repeated reads return the same values`() {
        val firstRam = DefaultDeviceCapacityReader.readTotalRamBytes(context)
        val firstDisk = DefaultDeviceCapacityReader.readTotalDiskBytes()

        assertThat(DefaultDeviceCapacityReader.readTotalRamBytes(context)).isEqualTo(firstRam)
        assertThat(DefaultDeviceCapacityReader.readTotalDiskBytes()).isEqualTo(firstDisk)
    }

    /**
     * After the first successful read the context is never consulted again, which is what keeps the
     * second `createDefault` of an SDK init from re-issuing the binder call. A mock that throws on
     * any use proves the cached path does no work.
     */
    @Test
    fun `ram is memoized after the first successful read`() {
        DefaultDeviceCapacityReader.readTotalRamBytes(context)

        val poisoned =
            mockk<Context> {
                every { applicationContext } throws AssertionError("reader consulted the context again")
            }

        assertThat(DefaultDeviceCapacityReader.readTotalRamBytes(poisoned)).isGreaterThanOrEqualTo(0L)
    }

    /**
     * The public one-arg entry point must wire the real reader through to the resource. Every other
     * resource test injects a fake, so without this the production path is never exercised end to
     * end.
     */
    @Test
    fun `createDefault populates the capacity attributes through the real reader`() {
        val resource = AndroidResource.createDefault(context)

        assertThat(resource.attributes.get(AndroidResource.SYSTEM_MEMORY_TOTAL))
            .`as`("total RAM on the resource")
            .isNotNull()
        assertThat(resource.attributes.get(AndroidResource.SYSTEM_DISK_TOTAL))
            .`as`("total disk on the resource")
            .isNotNull()
    }

    /**
     * A failed read must not be cached, so a transient failure can recover on the next resource
     * build rather than being pinned for the process lifetime.
     */
    @Test
    fun `a failed ram read is not cached`() {
        val failing =
            mockk<Context> {
                every { applicationContext } throws IllegalStateException("no ActivityManager")
            }

        assertThat(DefaultDeviceCapacityReader.readTotalRamBytes(failing)).isNegative()
        // The real context still succeeds afterwards, proving the failure was not memoized.
        assertThat(DefaultDeviceCapacityReader.readTotalRamBytes(context)).isGreaterThanOrEqualTo(0L)
    }
}
