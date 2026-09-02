/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.systemmetrics

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically captures a snapshot of all system + device metrics and emits a standalone
 * `"app.metrics"` span containing the data as span attributes.
 *
 * **CPU min/max tracking:**
 * A 1-second sub-sampler feeds a rolling window for CPU usage. Each emission includes
 * the min and max CPU observed since the last emission.
 *
 * **Device metrics caching:**
 * Expensive device metrics (PSS, battery, disk, RAM) are refreshed every 60 seconds on
 * a background cache timer to keep emit latency under 1ms.
 */
internal class SystemMetricsSpanEmitter(
    private val openTelemetry: OpenTelemetry,
    private val scheduler: ScheduledExecutorService,
    private val intervalSeconds: Long,
    private val cpuReader: CpuMetricsReader = CpuMetricsReader(),
    private val memoryReader: MemoryMetricsReader = MemoryMetricsReader(),
    private val threadReader: ThreadMetricsReader = ThreadMetricsReader(),
    private val deviceReader: DeviceMetricsReader,
) {

    private val tracer =
        openTelemetry.tracerProvider.get("io.opentelemetry.android.system-metrics")

    // Device metrics cache — refreshed at startup then every 60 s by the cache timer.
    // Defaults are -1 to indicate "not yet sampled" rather than 0 which is a valid reading.
    @Volatile private var cachedFootprintBytes = -1L
    @Volatile private var cachedAvailableRamBytes = -1L
    @Volatile private var cachedLowMemoryFlag = -1L
    @Volatile private var cachedBatteryPercent = -1.0
    @Volatile private var cachedBatteryTempCelsius = -1.0
    @Volatile private var cachedDiskFreeBytes = -1L

    // CPU sub-sampling window — accessed exclusively from the single scheduler thread.
    private var lastCpuSample = 0.0
    private var cpuMin = Double.MAX_VALUE
    private var cpuMax = 0.0

    fun start() {
        // 1-second sub-sampler — populates the CPU min/max window between emissions.
        @Suppress("DiscouragedApi")
        scheduler.scheduleAtFixedRate(::sampleCpu, 1L, 1L, TimeUnit.SECONDS)

        // Main emit timer: emit standalone span every intervalSeconds.
        @Suppress("DiscouragedApi")
        scheduler.scheduleAtFixedRate(::emitMetrics, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)

        // Cache refresh timer: runs immediately at startup then every 60 seconds so that
        // the first emission (at intervalSeconds) uses fresh device values rather than -1 defaults.
        @Suppress("DiscouragedApi")
        scheduler.scheduleAtFixedRate(::refreshDeviceCache, 0L, 60L, TimeUnit.SECONDS)
    }

    /** Runs every 1 second to track CPU min/max between emissions. */
    private fun sampleCpu() {
        val cpu = cpuReader.readCpuUsagePercent()
        lastCpuSample = cpu
        if (cpu < cpuMin) cpuMin = cpu
        if (cpu > cpuMax) cpuMax = cpu
    }

    private fun resetCpuWindow() {
        cpuMin = Double.MAX_VALUE
        cpuMax = 0.0
    }

    private fun emitMetrics() {
        val sample =
            ProcessSample(
                cpuUsage = lastCpuSample,
                cpuMin = cpuMin.takeIf { it != Double.MAX_VALUE } ?: 0.0,
                cpuMax = cpuMax,
                heapUsed = memoryReader.readHeapUsedBytes(),
                heapAllocated = memoryReader.readHeapAllocatedBytes(),
                heapFree = memoryReader.readHeapFreeBytes(),
                nativeUsed = memoryReader.readNativeHeapUsedBytes(),
                threadCount = threadReader.readThreadCount(),
            )
        resetCpuWindow()
        emitStandaloneSpan(sample)
    }

    private fun emitStandaloneSpan(sample: ProcessSample) {
        tracer
            .spanBuilder("app.metrics")
            .setSpanKind(SpanKind.INTERNAL)
            .setAllAttributes(buildAttributes(sample))
            .startSpan()
            .end()
        RumDiagnostics.d {
            "systemMetrics: emit cpu=${sample.cpuUsage} threads=${sample.threadCount}"
        }
    }

    private fun buildAttributes(sample: ProcessSample): Attributes =
        Attributes
            .builder()
            .put(ATTR_CPU_USAGE, sample.cpuUsage)
            .put(ATTR_CPU_MIN, sample.cpuMin)
            .put(ATTR_CPU_MAX, sample.cpuMax)
            .put(ATTR_HEAP_USED, sample.heapUsed)
            .put(ATTR_HEAP_ALLOCATED, sample.heapAllocated)
            .put(ATTR_HEAP_FREE, sample.heapFree)
            .put(ATTR_NATIVE_USED, sample.nativeUsed)
            .put(ATTR_THREAD_COUNT, sample.threadCount)
            .put(ATTR_FOOTPRINT, cachedFootprintBytes)
            .put(ATTR_SYS_MEM_AVAILABLE, cachedAvailableRamBytes)
            .put(ATTR_SYS_MEM_LOW, cachedLowMemoryFlag)
            .put(ATTR_BATTERY_LEVEL, cachedBatteryPercent)
            .put(ATTR_BATTERY_TEMP, cachedBatteryTempCelsius)
            .put(ATTR_DISK_FREE, cachedDiskFreeBytes)
            .build()

    private fun refreshDeviceCache() {
        try {
            cachedFootprintBytes = memoryReader.readFootprintBytes()
            // Single IPC per resource type instead of one call per individual metric.
            // mem.totalBytes / disk.totalBytes are intentionally unread here: total RAM/disk are
            // static device facts reported on the resource (AndroidResource) instead of on this
            // per-sample span. Note this saves no IPC — the totals are fields on the same result
            // objects already fetched below — it only keeps two static longs off every sample.
            val mem = deviceReader.readDeviceMemoryInfo()
            cachedAvailableRamBytes = mem.availableBytes
            cachedLowMemoryFlag = mem.lowMemoryFlag
            val battery = deviceReader.readBatteryInfo()
            cachedBatteryPercent = battery.percent
            cachedBatteryTempCelsius = battery.temperatureCelsius
            val disk = deviceReader.readDiskInfo()
            cachedDiskFreeBytes = disk.freeBytes
        } catch (_: Exception) {
            // Silent fail — stale cache values are used until next refresh
        }
    }

    private data class ProcessSample(
        val cpuUsage: Double,
        val cpuMin: Double,
        val cpuMax: Double,
        val heapUsed: Long,
        val heapAllocated: Long,
        val heapFree: Long,
        val nativeUsed: Long,
        val threadCount: Long,
    )

    companion object {
        // Metric names — used as AttributeKey names and in tests.
        const val METRIC_CPU_USAGE = "process.cpu.usage"
        const val METRIC_CPU_MIN = "process.cpu.usage.min"
        const val METRIC_CPU_MAX = "process.cpu.usage.max"
        const val METRIC_HEAP_USED = "process.memory.heap.used"
        const val METRIC_HEAP_ALLOCATED = "process.memory.heap.allocated"
        const val METRIC_HEAP_FREE = "process.memory.heap.free"
        // Not renamed to the canonical "process.memory.resident": the value here is
        // Debug.getNativeHeapAllocatedSize() (native heap allocated via malloc/JNI), not resident
        // set size — a real, physically-mapped-memory statistic this SDK does not currently
        // measure. Adopting the canonical name for the wrong quantity would be worse than keeping
        // the old one, since a chart comparing this against a genuine RSS value (e.g. iOS
        // resident_size) would silently compare two different things.
        const val METRIC_NATIVE_USED = "process.memory.native.used"
        const val METRIC_FOOTPRINT = "process.memory.footprint"
        const val METRIC_THREAD_COUNT = "process.thread.count"
        const val METRIC_SYS_MEM_AVAILABLE = "system.memory.available"
        const val METRIC_SYS_MEM_LOW = "system.memory.low"
        const val METRIC_BATTERY_TEMP = "system.battery.temperature"

        // Process — CPU
        val ATTR_CPU_USAGE: AttributeKey<Double> = AttributeKey.doubleKey(METRIC_CPU_USAGE)
        val ATTR_CPU_MIN: AttributeKey<Double> = AttributeKey.doubleKey(METRIC_CPU_MIN)
        val ATTR_CPU_MAX: AttributeKey<Double> = AttributeKey.doubleKey(METRIC_CPU_MAX)

        // Process — heap (current values only)
        val ATTR_HEAP_USED: AttributeKey<Long> = AttributeKey.longKey(METRIC_HEAP_USED)
        val ATTR_HEAP_ALLOCATED: AttributeKey<Long> = AttributeKey.longKey(METRIC_HEAP_ALLOCATED)
        /**
         * Deliberately *not* [RumConstants.HEAP_FREE_KEY], unlike [ATTR_BATTERY_LEVEL] and
         * [ATTR_DISK_FREE] below, which still alias the shared crash-schema keys.
         *
         * Canonical names this field `process.memory.heap.free` on `app.metrics` while keeping the
         * short `heap.free` on `device.crash`, so the two signals can no longer share one object.
         * Declaring it locally — the same way every non-shared metric here is declared — leaves
         * `RumConstants.HEAP_FREE_KEY` untouched for the crash reporter and keeps this out of the
         * public API surface, since this class is `internal`.
         */
        val ATTR_HEAP_FREE: AttributeKey<Long> = AttributeKey.longKey(METRIC_HEAP_FREE)
        val ATTR_NATIVE_USED: AttributeKey<Long> = AttributeKey.longKey(METRIC_NATIVE_USED)
        val ATTR_FOOTPRINT: AttributeKey<Long> = AttributeKey.longKey(METRIC_FOOTPRINT)
        val ATTR_THREAD_COUNT: AttributeKey<Long> = AttributeKey.longKey(METRIC_THREAD_COUNT)

        // Device
        // system.memory.total / system.disk.total moved to the resource (AndroidResource) — they
        // are static device facts, not per-sample metrics; no ATTR_*/METRIC_* constants for them
        // remain in this class.
        val ATTR_SYS_MEM_AVAILABLE: AttributeKey<Long> = AttributeKey.longKey(METRIC_SYS_MEM_AVAILABLE)
        val ATTR_SYS_MEM_LOW: AttributeKey<Long> = AttributeKey.longKey(METRIC_SYS_MEM_LOW)
        // Reuse RumConstants.BATTERY_PERCENT_KEY ("battery.percent") — matches crash instrumentation schema.
        val ATTR_BATTERY_LEVEL: AttributeKey<Double> = RumConstants.BATTERY_PERCENT_KEY
        val ATTR_BATTERY_TEMP: AttributeKey<Double> = AttributeKey.doubleKey(METRIC_BATTERY_TEMP)
        // Reuse RumConstants.STORAGE_SPACE_FREE_KEY ("storage.free") — matches crash instrumentation schema.
        val ATTR_DISK_FREE: AttributeKey<Long> = RumConstants.STORAGE_SPACE_FREE_KEY
    }
}
