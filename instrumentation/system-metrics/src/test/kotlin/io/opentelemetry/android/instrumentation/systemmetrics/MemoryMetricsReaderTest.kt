/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import java.io.File
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MemoryMetricsReaderTest {
    @Test
    fun `readHeapUsedBytes returns positive value`() {
        val reader = MemoryMetricsReader()
        assertThat(reader.readHeapUsedBytes()).isGreaterThan(0L)
    }

    @Test
    fun `readNativeHeapUsedBytes returns non-negative value`() {
        val reader = MemoryMetricsReader()
        assertThat(reader.readNativeHeapUsedBytes()).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `readHeapUsedBytes is less than or equal to readHeapAllocatedBytes`() {
        val reader = MemoryMetricsReader()
        assertThat(reader.readHeapUsedBytes()).isLessThanOrEqualTo(reader.readHeapAllocatedBytes())
    }

    @Test
    fun `readHeapFreeBytes is non-negative`() {
        val reader = MemoryMetricsReader()
        assertThat(reader.readHeapFreeBytes()).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `heap reader integrates with OTel gauge callback`() {
        val metricReader = InMemoryMetricReader.create()
        val meterProvider =
            SdkMeterProvider
                .builder()
                .registerMetricReader(metricReader)
                .build()
        val meter = meterProvider.get("io.opentelemetry.android.system-metrics")
        val reader = MemoryMetricsReader()

        meter
            .gaugeBuilder("test.heap.used")
            .setUnit("By")
            .ofLongs()
            .buildWithCallback { m -> m.record(reader.readHeapUsedBytes()) }

        metricReader.forceFlush()
        val metrics = metricReader.collectAllMetrics()

        assertThat(metrics).anyMatch { it.name == "test.heap.used" }
        val heapMetric = metrics.first { it.name == "test.heap.used" }
        assertThat(heapMetric.longGaugeData.points).isNotEmpty
        assertThat(heapMetric.longGaugeData.points.first().value).isGreaterThan(0L)
    }

    // ---- resident set size ------------------------------------------------------------------

    private val realStatus =
        """
        Name:	app_process
        Umask:	0077
        State:	S (sleeping)
        VmPeak:	 6721560 kB
        VmSize:	 6656024 kB
        VmRSS:	  123456 kB
        RssAnon:	   45678 kB
        RssFile:	   77778 kB
        RssShmem:	       0 kB
        Threads:	28
        """.trimIndent()

    @Test
    fun `parseVmRssKb reads the VmRSS line`() {
        assertThat(MemoryMetricsReader.parseVmRssKb(realStatus)).isEqualTo(123_456L)
    }

    @Test
    fun `parseVmRssKb does not confuse VmRSS with the RssAnon breakdown`() {
        // RssAnon/RssFile/RssShmem sum to the same total on some kernels; a loose match would
        // report a component as the whole.
        assertThat(MemoryMetricsReader.parseVmRssKb(realStatus)).isNotEqualTo(45_678L)
    }

    @Test
    fun `parseVmRssKb returns null when the line is absent`() {
        assertThat(MemoryMetricsReader.parseVmRssKb("Name:\tfoo\nThreads:\t3")).isNull()
    }

    @Test
    fun `parseVmRssKb returns null on a malformed value`() {
        assertThat(MemoryMetricsReader.parseVmRssKb("VmRSS:\t   notanumber kB")).isNull()
    }

    @Test
    fun `parseVmRssKb handles a value above two gigabytes`() {
        // 4 GB in kB overflows Int; the parse must stay in Long.
        assertThat(MemoryMetricsReader.parseVmRssKb("VmRSS:\t 4194304 kB")).isEqualTo(4_194_304L)
    }

    @Test
    fun `readResidentSetSizeBytes converts kB to bytes`() {
        val f = File.createTempFile("status", null)
        f.writeText(realStatus)
        assertThat(MemoryMetricsReader().readResidentSetSizeBytes(f)).isEqualTo(123_456L * 1024L)
        f.delete()
    }

    @Test
    fun `readResidentSetSizeBytes reports UNAVAILABLE when the file is missing`() {
        val missing = File("/definitely/not/a/real/path/status")
        assertThat(MemoryMetricsReader().readResidentSetSizeBytes(missing))
            .isEqualTo(MemoryMetricsReader.UNAVAILABLE)
    }

    @Test
    fun `readResidentSetSizeBytes reports UNAVAILABLE when VmRSS is absent`() {
        val f = File.createTempFile("status", null)
        f.writeText("Name:\tfoo\nThreads:\t3")
        assertThat(MemoryMetricsReader().readResidentSetSizeBytes(f))
            .isEqualTo(MemoryMetricsReader.UNAVAILABLE)
        f.delete()
    }
}
