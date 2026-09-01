# System Metrics Instrumentation

Status: development

This instrumentation periodically captures a snapshot of CPU, memory, thread, and device
metrics for the running process and device, and always emits them as a named event `"app.metrics"`
on a standalone `"app.metrics"` span. The collection interval defaults to 30 seconds and is
configurable via the `android-agent` DSL.

This instrumentation is **not** included in `android-agent` by default. It must be added
explicitly as a dependency.

## Telemetry

Data produced by this instrumentation uses instrumentation scope name
`io.opentelemetry.android.system-metrics`.

### Metrics snapshot

* Type: Standalone Span with an `"app.metrics"` event
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
| `process.memory.resident` | Long | Native heap bytes allocated via malloc/JNI |
| `process.memory.footprint` | Long | Proportional Set Size in **kB** (cached; refreshed every 60 s) |
| `process.thread.count` | Long | Total live threads in this process |
| `system.memory.total` | Long | Total physical RAM on the device (bytes) |
| `system.memory.available` | Long | Available (free) RAM on the device (bytes) |
| `system.memory.low` | Long | `1` if the device is in a low-memory state, `0` otherwise |
| `battery.percent` | Double | Battery charge level % (0–100) |
| `system.battery.temperature` | Double | Battery temperature in °C |
| `storage.free` | Long | Free disk space on the internal data partition (bytes) |
| `system.disk.total` | Long | Total disk space on the internal data partition (bytes) |

> `heap.free`, `battery.percent`, and `storage.free` reuse the attribute keys already
> defined in `RumConstants` so they align with the crash instrumentation schema.

## How it works

On each collection tick the emitter creates an instant span named `"app.metrics"`, attaches
the metrics snapshot as an event with the same name, and immediately ends the span.

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
