# System Metrics Instrumentation

Status: development

This instrumentation periodically captures a snapshot of CPU, memory, thread, and device
metrics for the running process and device, and always emits them as attributes directly on a
standalone `"app.metrics"` span. The collection interval defaults to 30 seconds and is
configurable via the `android-agent` DSL.

This instrumentation is **not** included in `android-agent` by default. It must be added
explicitly as a dependency.

## Telemetry

Data produced by this instrumentation uses instrumentation scope name
`io.opentelemetry.android.system-metrics`.

### Metrics snapshot

* Type: Standalone Span with metrics as span attributes
* Name: `app.metrics`
* Description: A point-in-time snapshot of process and device health metrics, emitted
  every 30 seconds by default (configurable).

#### Attributes

| Attribute | Type | Description |
|---|---|---|
| `process.cpu.usage` | Double | CPU usage % since the previous sample (0–100) |
| `process.cpu.usage.min` | Double | Minimum CPU % sampled in the collection window |
| `process.cpu.usage.max` | Double | Maximum CPU % sampled in the collection window |
| `process.memory.heap.used` | Long | Java heap bytes currently in use |
| `process.memory.heap.allocated` | Long | Java heap bytes committed from the OS |
| `process.memory.heap.free` | Long | Java heap bytes committed but unused |
| `process.memory.native.used` | Long | Native heap bytes allocated via malloc/JNI — a different statistic from `process.memory.resident`, not a rename of it (see note below) |
| `process.memory.resident` | Long | Resident set size in **bytes** — pages mapped into physical RAM, from `/proc/self/status` `VmRSS`. **Conditional**: omitted when the reading is unavailable (see note below) |
| `process.memory.footprint` | Long | Proportional Set Size in **bytes** (cached; refreshed every 60 s) |
| `process.thread.count` | Long | Total live threads in this process |
| `system.memory.available` | Long | Available (free) RAM on the device (bytes) |
| `system.memory.low` | Long | `1` if the device is in a low-memory state, `0` otherwise |
| `battery.percent` | Double | Battery charge level % (0–100) |
| `system.battery.temperature` | Double | Battery temperature in °C |
| `storage.free` | Long | Free disk space on the internal data partition (bytes) |

> `system.memory.total` (total device RAM) and `system.disk.total` (total disk capacity) are
> **not** in this table — they are static device facts, not per-sample metrics, so they moved to
> the OTel resource (`AndroidResource.SYSTEM_MEMORY_TOTAL`/`SYSTEM_DISK_TOTAL`) and are read once
> per process instead of on every `app.metrics` emission. Per the resource-export rules, they are
> present on logs and metrics always, and on trace spans only via the first cold `app.start` span
> — not on `app.metrics` or any other trace span. A query that derived used memory as
> `1 - available/total` from a single `app.metrics` record must now join against the resource.

> `battery.percent` and `storage.free` reuse the attribute keys already defined in `RumConstants`
> so they align with the crash instrumentation schema. `process.memory.heap.free` does not: the
> crash schema keeps `heap.free`, and this signal uses the canonical name, so the two are
> deliberately different keys for the same underlying value.
>
> `process.memory.native.used` and `process.memory.resident` are two genuinely different
> statistics, both emitted, and neither is a rename of the other. `native.used` is native heap
> allocated via malloc/JNI (`Debug.getNativeHeapAllocatedSize()`); `resident` is resident set size
> — pages mapped into physical RAM, read from `/proc/self/status`'s `VmRSS` line. `resident` is the
> canonical field and the one that compares like-for-like with iOS `resident_size`, so a
> cross-platform memory chart wants that one. Adopting the canonical name for the native-heap
> figure would have made any such chart silently wrong, which is why the rename was reversed.
>
> `VmRSS` is read rather than `/proc/self/statm` because `statm` reports resident *pages* and needs
> a page-size multiplier — Android 15 supports 16 kB pages, so the customary hardcoded 4096 is
> wrong there. `VmRSS` is denominated in kB, so there is no page-size assumption to get wrong.
>
> `process.memory.resident` is **omitted**, not zeroed, when the reading is unavailable (procfs
> unreadable, or no `VmRSS` line). A live process never has zero resident pages, so a `0` would be
> indistinguishable from a real measurement; treat an absent attribute as "not measured".

## How it works

On each collection tick the emitter creates an instant span named `"app.metrics"`, attaches
the metrics snapshot directly as span attributes, and immediately ends the span.

CPU min/max are tracked by a 1-second sub-sampler that runs between collection ticks,
so each emission includes the full min/max window rather than a single instantaneous reading.

Expensive device metrics (PSS, battery, RAM, disk) are refreshed on a separate 60-second
cache timer so the hot-path emit stays under 1 ms.

## Installation

This instrumentation is **not** bundled with `android-agent`. Add it as an explicit
dependency:

```kotlin
implementation("com.vunetsystems.opentelemetry.android.instrumentation:system-metrics:1.0.0")
```

Because the instrumentation is discovered at runtime via `ServiceLoader` (`@AutoService`),
no manual wiring is required — adding the dependency is sufficient.

### Configuring via `android-agent`

When using `android-agent`, the collection interval can be changed through the Kotlin DSL:

```kotlin
OpenTelemetryRum.builder(application, config)
    .addPlatform(AndroidSdkFetcher { androidAgent {
        instrumentation {
            systemMetrics {
                collectionIntervalSeconds(60L)
            }
        }
    }})
    .build()
```

To disable the instrumentation entirely:

```kotlin
instrumentation {
    systemMetrics {
        enabled(false)
    }
}
```

## Uninstalling

Call `uninstall()` to shut down the background scheduler and release resources:

```kotlin
instrumentation.uninstall(context, openTelemetryRum)
```
