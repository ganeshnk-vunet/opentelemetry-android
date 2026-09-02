# OpenTelemetry Android Changelog

## Unreleased

### Added

- OkHttp network phase timing (incubating): DNS, connect, TLS, TTFB, download, and total durations exported as `http.client.timing.*` span attributes and `http.*` span events when `captureNetworkTimingPhases` is enabled (default).
- HttpURLConnection total request timing (incubating): `http.client.timing.total_ms` and `http.call` span event when `captureNetworkTiming` is enabled (default); `http.client.timing.phases_supported=false` (use OkHttp for phase breakdown).
- HTTP error taxonomy: OkHttp and HttpURLConnection failed spans include `http.error.category` (`timeout`, `dns`, `ssl`, `io`, `http_client`, `unknown`) alongside existing `error.type`.
- Network monitoring: `network.connection.metered` boolean on spans and `network.change` events when the active network is known (replaces legacy `net.host.connection.metered`).
- Image-load target attribution: Glide and Coil `image.load` spans include `image.target.view_id` (resource entry name; `no-id` for views without an `android:id`, `unresolved` for runtime `View.generateViewId()` ids that have no resource-table entry) and `image.target.view_type` when the request has a view-backed target, so a failing image can be traced to a specific widget rather than only to `screen.name`. Compose call sites (`AsyncImage`, `GlideImage`) have no backing `View` and omit both.
- Hybrid-click gesture attribution: `ui.interaction` spans include `interaction.type`, the canonical
  discriminator naming the gesture that produced the span. Values are `tap` and `long_press`, split
  by press duration against `ViewConfiguration.getLongPressTimeout()`. The attribute describes the
  user's gesture rather than what the app did with it: a slow press on a target with no long-click
  handler reports `long_press` even though the app handled an ordinary click. Gestures that leave
  the touch slop are still not reported at all, so span volume is unchanged.
- Hybrid-click control classification: `ui.interaction` spans include `ui.control.type` (the
  canonical successor to `app.widget.type`, carrying the identical value — both are emitted) and
  `ui.control.selection_mode` (`single` for radio/tab/dropdown, `multiple` for switch/checkbox/
  toggle, omitted for kinds where selection doesn't apply). The selection mode reflects what each
  widget *kind* means, not per-instance state — a radio button is `single` regardless of whether
  it's actually grouped with others.
- Image-load error taxonomy: failed `image.load` spans include the standard semconv `error.type` (fully-qualified class of the failure) as a queryable attribute alongside the existing `recordException` span event, which most back-ends cannot group on. Reusing the semconv key means image failures join the same error breakdowns as the OkHttp and HttpURLConnection instrumentations. Glide reports the first root cause rather than the generic wrapping `GlideException`.
- Jank type attribution: `app.jank` spans include `app.jank.type` (`slow` or `frozen`). Bucketing is cumulative — a frozen frame exceeds both thresholds and is reported by both spans — so counting `app.jank` spans double-counts frozen frames. Previously the only way to tell the two apart was matching the `app.jank.threshold` float (`0.016` vs `0.7`); this makes it a group-by. Purely additive — no existing attribute changed. Deliberate extension: semconv owns `app.jank.*` but defines only `frame_count`, `period`, and `threshold`. Migrating off deprecated `slowRenders`/`frozenRenders`: those bucket exclusively, so equivalent slow-only frames are `sum(app.jank.frame_count)` where `type="slow"` minus `sum(app.jank.frame_count)` where `type="frozen"` — do not subtract span counts.
- Fault runtime attribution: `device.crash` and `device.anr` spans include `error.runtime` (`RumConstants.ERROR_RUNTIME_KEY`), always `jvm` from this SDK. A Dart/`FlutterError` or React Native exception rethrown into the Android uncaught handler is still `jvm`. Grouping Flutter/RN faults separately only works if those wrappers emit their own `device.crash` / `device.anr` (or overwrite via `addAttributesExtractor`) using `dart` / `js`. The value space is documented as `jvm` / `dart` / `js` (`ERROR_RUNTIME_JVM`, `ERROR_RUNTIME_DART`, `ERROR_RUNTIME_JS`) so wrappers that emit their own spans can copy the same lowercase runtime names rather than picking their own spelling; the values name the runtime, not the UI framework, which is reported separately as `app.framework`. `error.runtime` is a deliberate extension — semconv owns `error.*` but defines only `error.type`. Purely additive — no existing attribute changed.

### ⚠️⚠️ Breaking changes

- **`app.metrics` no longer carries its data on a span event — this is not a rename, and a
  rename-style fix does not apply.** All 16 metric attributes (`process.cpu.usage`,
  `process.memory.*`, `process.thread.count`, `system.memory.*`, `battery.percent`,
  `system.battery.temperature`, `storage.free`, `system.disk.total`, etc.) move from the
  `"app.metrics"` **event** attached to the `app.metrics` span to direct **attributes on the span
  itself**. The `"app.metrics"` event is removed entirely, not left empty.

  Every attribute keeps its existing name and value — nothing to search-and-replace. Any
  dashboard, alert, or query reading these values via `event.attributes` for the `app.metrics`
  span sees the data disappear, not move under a new key, because it's no longer in that OTLP
  location at all. Consumers must instead read `span.attributes` directly on the `app.metrics`
  span. Span name, span kind, and emission timing (default 30s) are unchanged.

  `SystemMetricsSpanEmitter` is `internal`; no public API / `apiCheck` impact.

- `app.metrics` renamed `heap.free` to the canonical `process.memory.heap.free`. `device.crash` and `device.anr` keep `heap.free` unchanged — canonical renames this field in `app.metrics` scope only. Both signals previously emitted through a single shared `RumConstants.HEAP_FREE_KEY`, so `SystemMetricsSpanEmitter` now declares its own key, the same way every non-shared metric there already does; `RumConstants.HEAP_FREE_KEY` is untouched, so the crash reporter and any caller using it are unaffected and the public API is unchanged. `storage.free` and `battery.percent` stay shared, as canonical keeps those identical on both signals. Update dashboards, alerts, and queries reading `heap.free` off `app.metrics`.
- **`system.memory.total` and `system.disk.total` move from `app.metrics` span attributes to the
  OTel resource — this is not a rename, and where you read them from is not symmetric with the
  rest of the resource.** They no longer appear anywhere on `app.metrics` (or any other trace
  span). Per the existing resource-export rules
  (`io.opentelemetry.android.export.SelectiveResourceSpanExporter`, unchanged by this release):
  logs and metrics always carry the full resource, so both keys are present there; traces carry
  the full resource only on the first cold `app.start` span per process, so that is the only trace
  span where they now appear. A consumer reading these two values from `app.metrics` must switch
  to the resource, and a consumer reading them from an arbitrary trace span must switch to
  filtering for the cold `app.start` span specifically.

  This does not reduce IPC. `system-metrics` still calls `getMemoryInfo()` and `StatFs` on its
  existing 60-second cache refresh — the totals were fields on those same result objects, never
  extra calls — so the emitter pays exactly what it paid before. What changes is placement: two
  static longs stop being repeated on every `app.metrics` sample.

  New unconditional cost: every app now performs one `ActivityManager.getMemoryInfo()` call and one
  `StatFs` call at SDK-init resource-build time, regardless of whether the opt-in
  `instrumentation:system-metrics` module is used — these are treated as device facts (same
  category as `device.manufacturer`), not something gated on which instrumentations are installed,
  since the resource has no mechanism for late, per-instrumentation attribute append. Both values
  are memoized after the first successful read, because `AndroidResource.createDefault` runs more
  than once per SDK init (a field initializer in `OpenTelemetryRumBuilder`, then again in
  `OpenTelemetryRumInitializer`) and typically on the main thread during `Application.onCreate`,
  where `StatFs` is a filesystem read that can trip `StrictMode.detectDiskReads`. A failed read is
  not memoized, so a transient failure can recover on a later build.

  If either value cannot be read it is **omitted** from the resource rather than reported as a
  sentinel: the resource is immutable for the life of the process, so publishing `-1` would pin it
  onto every log and metric until the app restarts.

  No public API change: `AndroidResource.createDefault(Context)` keeps its existing signature, and
  the reader it delegates to is `internal` to `:core`.

- `app.start` attribute and startup-phase span events renamed to the canonical `app.start.*`
  names. Update dashboards, alerts, and queries keyed on the old names:

  | Old | New |
  |-----|-----|
  | `start.type` (attribute) | `app.start.type` |
  | `app.process.creation` | `app.start.phase.process` |
  | `app.attach_base_context.start` | `app.start.phase.attach_base_context.start` |
  | `app.attach_base_context.end` | `app.start.phase.attach_base_context.end` |
  | `app.content_providers.start` | `app.start.phase.content_providers.start` |
  | `app.content_providers.end` | `app.start.phase.content_providers.end` |
  | `applicationCreated` | `app.start.phase.sdk_init` |
  | `applicationPostCreated` | `app.start.phase.first_activity` |
  | `ttid` | `app.start.phase.initial_display` |

  Every startup milestone now lives under `app.start.phase.*`, and the two phases that report
  both a boundary and an end keep them under one shared prefix
  (`app.start.phase.attach_base_context.*`, `app.start.phase.content_providers.*`) so a duration
  query can pair them by stripping `.start` / `.end`.

  Each name describes the probe it is taken from rather than a generic startup phase:
  `attach_base_context.end` is the first `Application` callback completing (the ART runtime is
  already up well before it), `content_providers.end` is the end of ContentProvider init,
  `sdk_init` is the instant the OTel SDK finished initialising partway through
  `Application.onCreate()`, and `first_activity` is the first `onActivityPreCreated` — which
  fires before `Activity.onCreate`, layout, or first paint. First paint is
  `app.start.phase.initial_display`.

  `RumConstants.START_TYPE_KEY` and the `AppStartupTimer.EVENT_*` constants keep their identifiers,
  so this changes the emitted wire keys only and is source- and binary-compatible for callers.
- **`app.metrics`'s `process.memory.pss` renamed to `process.memory.footprint`, in bytes.**
  Canonical defines footprint in bytes — the same field iOS feeds from `phys_footprint`, itself
  bytes — so the conversion ships in this same change rather than being deferred: unlike a rename
  that reuses an already-populated key, `process.memory.footprint` has no existing readers to
  protect from a silent 1024× shift, since nobody has ever emitted this key name before. Deferring
  the conversion would instead have made it wrong from day one for anyone building a new query
  against the canonical name expecting the canonical unit. `process.memory.pss` (kB) is removed;
  update dashboards, alerts, and queries keyed on it. `SystemMetricsSpanEmitter` is `internal`, so
  no `apiCheck`/`apiDump` is affected.

  `MemoryMetricsReader.readPssKb()` is renamed to `readFootprintBytes()`; the kB→bytes
  multiplication is pulled out as `MemoryMetricsReader.pssKbToBytes` so it's directly
  unit-testable.

- **`process.memory.native.used` is *not* renamed to `process.memory.resident`, on reflection.**
  An earlier draft of this change proposed that rename, but the value behind it —
  `Debug.getNativeHeapAllocatedSize()`, native heap allocated via malloc/JNI — is not resident set
  size (pages currently mapped into physical RAM); those are different statistics. Shipping the
  canonical name against the wrong quantity would make any chart comparing it against a real RSS
  value (e.g. iOS `resident_size`) silently wrong. `process.memory.native.used` stays as-is until
  this SDK can emit a genuine RSS reading under the canonical name.

- Action summary renamed to the canonical `semantic.summary` (was `app.action.summary`). This is the human-readable span description written by `ActionSummarySpanExporter` (e.g. `App cold start`). Update dashboards, alerts, and queries keyed on the old name. `RumConstants.APP_ACTION_SUMMARY_KEY` keeps its identifier, so this changes the emitted wire key only and is source- and binary-compatible for callers.

- Hybrid-click span renamed from `ui.click` to `ui.interaction`
  (`RumConstants.UI_INTERACTION_SPAN_NAME`). Update dashboards, alerts, and queries keyed on the
  old name. Span attributes and the derived summary *value* are unchanged (the attribute carrying
  that summary is renamed to `semantic.summary` — see the entry above).

- `ui.interaction` toggle-state attribute renamed from `app.widget.checked` to
  `ui.control.value.checked` (canonical `ui.control.value.*` family). Update dashboards, alerts,
  and queries keyed on the old name. Internal-only constant identifier unchanged, so this is a
  wire-key-only change.

- Trace spans no longer repeat full device/OS resource attributes on every export. Only the first
  cold `app.start` span includes the full OTLP resource block; other trace spans use a minimal
  resource (`service.name` + SDK defaults). Logs and metrics are unchanged. Query `device.*` /
  `os.*` on traces via the cold `app.start` span or from logs/metrics resource.

### Fixed

- OkHttp Byte Buddy advice classes (`OkHttpClientAdvice`, `OkHttpCallbackAdvice`) now ship in `okhttp3-library` so woven `OkHttpClient` bytecode resolves them at runtime (fixes `NoClassDefFoundError` on Android).
- OkHttp client instrumentation logic moved to public `OkHttpSingletons.applyClientInstrumentation` so woven OkHttp bytecode does not invoke private advice helpers (fixes `IllegalAccessError` on Android).
- Glide image loads that fail with a `null` model (e.g. `Glide.with(view).load(null)`) no longer drop the failure silently; a span is now synthesised with `image.url` and `image.model_type` set to `unknown`.

### ⚠️⚠️ Breaking changes

- Published Maven artifact for startup runtime instrumentation renamed from `startup` to
  `startup-library` (module layout now mirrors `okhttp3-library` / `okhttp3-agent`). Direct
  consumers must update coordinates; `startup-agent` is unchanged.

- Removed misleading `app.base_context` span event (it did not measure `attachBaseContext`).
  Added `app.attach_base_context.start` / `app.attach_base_context.end` events (requires
  `startup-agent` + Byte Buddy) and `app.content_providers.start` / `app.content_providers.end`
  events for the ContentProvider phase. Removed legacy `app.init.contentprovider` and
  `applicationPreCreated` AppStart span events (use `app.content_providers.end` instead).
  `StartupTimestampProvider.attachBaseContextEpochMs` renamed
  to `contentProvidersPhaseStartEpochMs`; added `attachBaseContextStartElapsedRealtime` and
  `attachBaseContextEndElapsedRealtime`.

- Activity and fragment lifecycle spans now use stable span names with an event attribute:
  - Activity lifecycle: span name `activity.lifecycle`, attribute `activity.lifecycle.event` (`Created`, `Resumed`, `Paused`, `Stopped`, `Destroyed`, `Restarted`)
  - Fragment lifecycle: span name `fragment.lifecycle`, attribute `fragment.lifecycle.event` (`Created`, `Restored`, `Resumed`, `Paused`, `Stopped`, `Destroyed`, `ViewDestroyed`, `Detached`)
  - App startup span renamed from `AppStart` to `app.start` (`RumConstants.APP_START_SPAN_NAME`)
  - Span events (`activityPreCreated`, `fragmentResumed`, etc.) are unchanged

## Version 1.3.0 (2026-04-22)

### ⚠️⚠️ Breaking changes

- The minimum supported Android SDK version has been increased from 21 to 23
  ([#1650](https://github.com/open-telemetry/opentelemetry-android/pull/1650))
- The minimum supported Kotlin version has increased from 1.8 to 2.0.
  ([#1489](https://github.com/open-telemetry/opentelemetry-android/pull/1489))

### 🚫 Deprecations

- Deprecate the sdk ready listener in favor of an api listener.
  ([#1597](https://github.com/open-telemetry/opentelemetry-android/pull/1597))

### Migration notes

We continue migrating many components from Java to Kotlin. While we expect to 
remain compatible with Java-based applications, Kotlin support is our 
first priority. See 
[KOTLIN_FIRST.md](https://github.com/open-telemetry/opentelemetry-android/blob/main/docs/KOTLIN_FIRST.md) 
for more information. 

We continue working to stabilize our API surface. In this release, the instrumentation API 
is not yet stable, and has undergone some notable changes that will impact (break) any
custom instrumentation that uses these APIs. This should stabilize in the near future.

### 📈 Enhancements

- Improved generalized flushing behavior of telemetry upon crash.
  ([#1610](https://github.com/open-telemetry/opentelemetry-android/pull/1610))
- Mark the `agent-api`, which contains the `OpenTelemetryRum` interface, as stable.
  ([#1612](https://github.com/open-telemetry/opentelemetry-android/pull/1612))
- Instrumentation API enhancements.
  ([#1645](https://github.com/open-telemetry/opentelemetry-android/pull/1645))
- Mark the `session` module as stable.
  ([#1690](https://github.com/open-telemetry/opentelemetry-android/pull/1690))
- Agent DSL now supports providing global attributes via `globalAttributesSupplier`.
  ([#1593](https://github.com/open-telemetry/opentelemetry-android/pull/1593))
- Agent DSL now supports customizing the exporter SSL context and trust manager.
  ([#1537](https://github.com/open-telemetry/opentelemetry-android/pull/1537))
- Provide guidance for AI-generated PRs and agentic contributions.
  ([#1640](https://github.com/open-telemetry/opentelemetry-android/pull/1640))
- The demo-app now includes an example of OkHttp build-time instrumentation.
  ([#1688](https://github.com/open-telemetry/opentelemetry-android/pull/1688))
- As part of the session API stabilization, several kotlin idiomatic changes were introduced.
  ([#1673](https://github.com/open-telemetry/opentelemetry-android/pull/1673))
- NetworkChange instrumentation now includes an uninstall() implementation.
  ([#1639](https://github.com/open-telemetry/opentelemetry-android/pull/1639))
- OkHttp instrumentation package has been modified.
  ([#1609](https://github.com/open-telemetry/opentelemetry-android/pull/1609))
- It is now possible to provide a client-side TLS certificate and leverage mTLS in exporters.
  ([#1660](https://github.com/open-telemetry/opentelemetry-android/pull/1660))

### 🛠️ Bug fixes

- The `core` module no longer forces `androidx.core` as a transitive dependency.
  ([#1665](https://github.com/open-telemetry/opentelemetry-android/pull/1665))
- The `service` module no longer depends on `androix.navigation`.
  ([#1668](https://github.com/open-telemetry/opentelemetry-android/pull/1668))

### 🧰 Tooling
 
- Release automation regex fix.
  ([#1613](https://github.com/open-telemetry/opentelemetry-android/pull/1613))

## Version 1.2.0 (2026-02-18)

### 📈 Enhancements

- Multiple instrumentation modules have been converted from Java to Kotlin, including ANR, activity,
  fragment, websocket, and network instrumentation.
  ([#1551](https://github.com/open-telemetry/opentelemetry-android/pull/1551),
  [#1552](https://github.com/open-telemetry/opentelemetry-android/pull/1552),
  [#1553](https://github.com/open-telemetry/opentelemetry-android/pull/1553),
  [#1554](https://github.com/open-telemetry/opentelemetry-android/pull/1554),
  [#1557](https://github.com/open-telemetry/opentelemetry-android/pull/1557),
  [#1570](https://github.com/open-telemetry/opentelemetry-android/pull/1570),
  [#1571](https://github.com/open-telemetry/opentelemetry-android/pull/1571),
  [#1572](https://github.com/open-telemetry/opentelemetry-android/pull/1572))
- Added metadata file for integration with the Google Play Console.
  ([#1578](https://github.com/open-telemetry/opentelemetry-android/pull/1578))
- Increase test coverage for sessions.
  ([#1583](https://github.com/open-telemetry/opentelemetry-android/pull/1583))

### 🛠️ Bug fixes

- Fix clock baseline computation to correctly use nanosecond offsets.
  ([#1574](https://github.com/open-telemetry/opentelemetry-android/pull/1574))

### 🧰 Tooling

- Adding support for patch and rc version bump during a release.
  ([#1538](https://github.com/open-telemetry/opentelemetry-android/pull/1538))

## Version 1.1.0 (2026-01-23)

This is a regular monthly release that builds on the following OpenTelemetry dependencies:

* OpenTelemetry java instrumentation [2.24.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.24.0).
* OpenTelemetry java contrib [1.53.0](https://github.com/open-telemetry/opentelemetry-java-contrib/releases/tag/v1.53.0).
* OpenTelemetry java [1.58.0](https://github.com/open-telemetry/opentelemetry-java/releases/tag/v1.58.0).

### 📈 Enhancements

- The OpenTelemetry Resource now contains `android.os.api_level`. The value is set 
  to the Android API level used by the application build.
  ([#1455](https://github.com/open-telemetry/opentelemetry-android/pull/1455))
- Add the ability to customize the OpenTelemetry resource via the OpenTelemetryRumInitializer DSL
  ([#1476](https://github.com/open-telemetry/opentelemetry-android/pull/1476))
- The OpenTelemetry Resource now contains the `app.installation.id` attribute. The value
  is set a random UUID which is persisted in SharedPreferences across launches.
  ([#1488](https://github.com/open-telemetry/opentelemetry-android/pull/1488))
- The OpenTelemetryRumInitializer now exposes a means of setting the OpenTelemetry 
  clock. Please note that most users will never need to do this, and an enhanced clock 
  implementation is provided as an internal detail.
  ([#1486](https://github.com/open-telemetry/opentelemetry-android/pull/1486))
- New OpenTelemetry clock implementation that is no longer impacted by deep sleeps.
  This yields more accurate elapsed time semantics for timeouts and background tasks.
  ([#1487](https://github.com/open-telemetry/opentelemetry-android/pull/1487))
- Retry exporting unsuccessfully exported batches
  ([#1493](https://github.com/open-telemetry/opentelemetry-android/pull/1493))

### 🛠️ Bug fixes

- Fix a concurrency issue in the Session Manager
  ([#1419](https://github.com/open-telemetry/opentelemetry-android/pull/1419))
- Add missing default method override in compose click instrumentation.
  ([#1464](https://github.com/open-telemetry/opentelemetry-android/pull/1464))
- Don't obfuscate fragment names
  ([#1490](https://github.com/open-telemetry/opentelemetry-android/pull/1490))

### 🧰 Tooling

- Leverage detekt type resolution for enahanced static analysis.
  ([#1463](https://github.com/open-telemetry/opentelemetry-android/pull/1463))

## Version 1.0.1 (2026-01-07)

* Patch release to drop `-alpha` suffix for the agent module. Sorry for any
  confusion this may have caused.

## Version 1.0.0 (2026-01-07)

⭐ Promote rc.1 to v1.0.0.

This release promotes v1.0.0-rc1 to v1.0.0 without any additional changes.
This release also denotes the first stable version of the OpenTelemetry
Android Agent!

Going forward, we will prevent breaking changes to the agent API until
the next major version. Other modules, including instrumentation, are still
marked as alpha and will each have an individual trajectory toward stability.

Thanks to everyone involved in making this stable release a success!

## Version 1.0.0-rc.1 (2025-11-25)

Good news, everyone! This denotes the first "stable" release candidate (`rc.1`)
of OpenTelemetry Android. In a future release, after we have concluded that
there are no significant issues or changes to the `android-agent` API,
we will drop the `rc` (release candidate) designation and consider the 
`android-agent` a "stable" release.

Take note that even after the `rc` designation is removed, most modules
will still contain an `-alpha` suffix to indicate that they are a future
stability target.

### Migration notes

Please note that, as part of our stabilization effort, we have introduced
a few breaking changes in this release. The `OpenTelemetryRumInitializer` API, 
which we expect most users to be leveraging, should _not_ have breaking 
changes in this release (with one exception noted below).

Users who may have been utilizing the `OpenTelemetryRumBuilder` class directly
through a transitive dependency from the `android-agent` module, without declaring
a direct dependency on the `core` module, will have compilation errors.
These errors can be resolved by declaring a direct gradle dependency on the
`core` module.

By default, the agent now enables gzip compression for exported data.
Most users should benefit from this, but if gzip is not desired it may 
be disabled by setting `compression = Compression.NONE` in the `httpExport`
block of the agent configuration. 

### ⚠️⚠️ Breaking changes

- The OpenTelemetryRumBuilder is now a Kotlin class.
  ([#1372](https://github.com/open-telemetry/opentelemetry-android/pull/1372))
- Gzip compression is now enabled by default for exporters.
  ([#1360](https://github.com/open-telemetry/opentelemetry-android/pull/1360))
- Drop `RUM_SDK_VERSION` in favour of `TELEMETRY_SDK_VERSION`
  ([#1365](https://github.com/open-telemetry/opentelemetry-android/pull/1365))
- Move OpenTelemetryRum to a new API module.
  ([#1387](https://github.com/open-telemetry/opentelemetry-android/pull/1387))

### 🌟 New instrumentation

- New instrumentation that reports screen orientation changes.
  ([#1333](https://github.com/open-telemetry/opentelemetry-android/pull/1333))

### 📈 Enhancements

- Activity and Fragment instrumentation may now be uninstalled.
  ([#1369](https://github.com/open-telemetry/opentelemetry-android/pull/1369))

### 🛠️ Bug fixes

- Add compat fix for obtaining the thread id on versions >= `BAKLAVA` (36).
  ([#1346](https://github.com/open-telemetry/opentelemetry-android/pull/1346))
- Rename event name `event.app.widget.click` to [`app.widget.click`](https://opentelemetry.io/docs/specs/semconv/app/app-events/#event-appwidgetclick)
  ([#1391](https://github.com/open-telemetry/opentelemetry-android/pull/1391))
- Add `openTelemetry` getter to `OpenTelemetryRum` to fix breaking API change
  ([#1373](https://github.com/open-telemetry/opentelemetry-android/pull/1373))
- Fix metrics aggregation temporality when using disk buffering.
  ([#1405](https://github.com/open-telemetry/opentelemetry-android/pull/1405))
- Fix instrumentation ordering problem, which could prevent `session.start` event from firing in some cases.
  ([#1413](https://github.com/open-telemetry/opentelemetry-android/pull/1413))
- Enable disk buffering by default.
  ([#1416](https://github.com/open-telemetry/opentelemetry-android/pull/1416))

### 🧰 Tooling

- Remove final remaining usages of mockito in favor of mockk.
  ([#1362](https://github.com/open-telemetry/opentelemetry-android/pull/1362))

## Version 0.16.0 (2025-10-24)

__Note: This version is not the first release candidate. We had previously announced that
the October 2025 release would be our first release candidate, but this effort is temporarily
paused.__

We are still soliciting feedback from users as we approach a 1.0.0 milestone and
mark the `android-agent` and `OpenTelemetryRumInitializer` api stable. Please see
[#1257](https://github.com/open-telemetry/opentelemetry-android/issues/1257)
to join the discussion.

The full list of commits included in this release
[may be viewed here](https://github.com/open-telemetry/opentelemetry-android/compare/release/v0.15.x...release/v0.16.x).

### ⚠️⚠️ Breaking changes
- Removing OTelRumConfig from initializer
  ([#1272](https://github.com/open-telemetry/opentelemetry-android/pull/1272))
- `SessionStorage` and `SessionIdGenerator` are now internal interfaces.
  ([#1278](https://github.com/open-telemetry/opentelemetry-android/pull/1278))

### 📣 Migration notes
- The agent initializer now uses a typesafe DSL for configuration parameters.
  Existing users of the initialization API may need to made some modifications,
  but we think this is a nice extensible pattern for the initializer. 
- Unstable APIs now leverage a new `@Incubating` annotation, which leverages the 
  kotlin compiler to emit warnings about use of unstable APIs
  ([#1238](https://github.com/open-telemetry/opentelemetry-android/pull/1238))

### 📈 Enhancements
- Add functional interfaces to support config DSL via agent initializer.
  ([#1275](https://github.com/open-telemetry/opentelemetry-android/pull/1275))
- Config for disabling default instrumentations via agent initializer.
  ([#1273](https://github.com/open-telemetry/opentelemetry-android/pull/1273))
- Enhanced detection of `service.name` when the application label is populated with build
  placeholders.
  ([#1302](https://github.com/open-telemetry/opentelemetry-android/pull/1302))

### 🧰 Tooling
- Testing now uses Marshmallow as a lower bound.
  ([#1230](https://github.com/open-telemetry/opentelemetry-android/pull/1230))
- Improve PR code coverage reporting by running codecov on main branch
  ([#1236](https://github.com/open-telemetry/opentelemetry-android/pull/1236))
- Update main CI build from Java 17 to Java 21.
  ([#1317](https://github.com/open-telemetry/opentelemetry-android/pull/1317))

## Version 0.15.0 (2025-09-18)

### ⚠️⚠️ Breaking changes

- Drop volley instrumentation.
  ([#1228](https://github.com/open-telemetry/opentelemetry-android/pull/1228))

### 📈 Enhancements

- Introduce configuration DSL for `OpenTelemetryRumInitializer`
  ([#1198](https://github.com/open-telemetry/opentelemetry-android/pull/1198))
- Refactor jank to use events instead of zero-duration spans  
  ([#1175](https://github.com/open-telemetry/opentelemetry-android/pull/1175))
- Add experimental ability to close Services
  ([#1196](https://github.com/open-telemetry/opentelemetry-android/pull/1196))
- Add more warning logs in Network detection  
  ([#1205](https://github.com/open-telemetry/opentelemetry-android/pull/1205))

### 🧰 Tooling

- Drop API 21 (Lollipop) test automation with Robolectric.
  ([#1189](https://github.com/open-telemetry/opentelemetry-android/pull/1189))

## Version 0.14.0 (2025-08-21)

### 📣 Migration notes

- Volley HTTP instrumentation is now marked as deprecated and will be removed in 0.20.0.
  Volley has [not seen a release in about 4 years](https://github.com/google/volley/releases)
  and it is unlikely that it has much adoption. As a result, we have chosen to halt development
  of the instrumentation in `opentelemetry-android`.
  [#1145](https://github.com/open-telemetry/opentelemetry-android/pull/1145)

### 🛠️ Bug fixes
- Allow empty global attributes from empty Supplier at startup
  ([#1102](https://github.com/open-telemetry/opentelemetry-android/pull/1102))
- Fix build warning for duplicate module namespace in manifest
  ([#1136](https://github.com/open-telemetry/opentelemetry-android/pull/1136))

### 📈 Enhancements
- Updated ANR data model from span to log event
  ([#1101](https://github.com/open-telemetry/opentelemetry-android/pull/1101))
- Experimental OpenTelemetryRum.shutdown() and instrumentation uninstall
  ([#1109](https://github.com/open-telemetry/opentelemetry-android/pull/1109))
- build: bump compileSdkVersion to 36
  ([#1122](https://github.com/open-telemetry/opentelemetry-android/pull/1122))
- Remove READ_PHONE_STATE permission and update docs
  ([#1129](https://github.com/open-telemetry/opentelemetry-android/pull/1129))
- Okhttp jvm android resolution
  ([#1155](https://github.com/open-telemetry/opentelemetry-android/pull/1155))
- Update network fetch - Use relevant APIs and permissions across different API Levels
  ([#1147](https://github.com/open-telemetry/opentelemetry-android/pull/1147))

## Version 0.13.0 (2025-07-24)

- Alter FilteringSpanExporter to leverage common code from contrib
  ([#1043](https://github.com/open-telemetry/opentelemetry-android/pull/1043))
- Instrumentation docs now include installation instructions
  ([#1068](https://github.com/open-telemetry/opentelemetry-android/pull/1068))
- OpenTelemetry Android BOM now includes upstream components (instrumentation, sdk, api)
  ([#1075](https://github.com/open-telemetry/opentelemetry-android/pull/1075))
- Update docs to reflect that desugaring is required for minSdk < 26
  ([#1085](https://github.com/open-telemetry/opentelemetry-android/pull/1085))
- Include service.version in the default AndroidResource
  ([#1087](https://github.com/open-telemetry/opentelemetry-android/pull/1087))

## Version 0.12.0 (2025-07-08)

### 🌟 New instrumentation

- Capture click events for compose
  ([#1002](https://github.com/open-telemetry/opentelemetry-android/pull/1002))
- Capture click events for non-compose views
  ([#953](https://github.com/open-telemetry/opentelemetry-android/pull/953))

### 📈 Enhancements

- Agent initialization api
  ([#945](https://github.com/open-telemetry/opentelemetry-android/pull/945))
- Enable disk buffering by default in the demo app
  ([#988](https://github.com/open-telemetry/opentelemetry-android/pull/988))
- Exposing SessionProvider setter
  ([#979](https://github.com/open-telemetry/opentelemetry-android/pull/979))
- Exposing instrumentation api as agent api
  ([#1007](https://github.com/open-telemetry/opentelemetry-android/pull/1007))
- Use semantic conventions in click instrumentation
  ([#1008](https://github.com/open-telemetry/opentelemetry-android/pull/1008))
- add convenience event emitting api to OpenTelemetryRum
  ([#892](https://github.com/open-telemetry/opentelemetry-android/pull/892))

### 🧰 Tooling

- Move SessionConfig up
  ([#959](https://github.com/open-telemetry/opentelemetry-android/pull/959))
- Remove runtime dep on androidx fragment navigiation from modules that don't strictly need it
  ([#961](https://github.com/open-telemetry/opentelemetry-android/pull/961))
- Agent default instrumentation config
  ([#976](https://github.com/open-telemetry/opentelemetry-android/pull/976))
- update sonatype urls
  ([#999](https://github.com/open-telemetry/opentelemetry-android/pull/999))

## Version 0.11.0 (2025-04-15)

### 📣 Migration notes

Please be aware that the maven coordinates for many instrumentation modules
have changed. Details can be found [here](https://github.com/open-telemetry/opentelemetry-android/pull/926).

### ⚠️⚠️ Breaking changes

- Remove `setSessionTimeout()` on `OtelRumConfig` in favor of new `setSessionConfig()`.([#887](https://github.com/open-telemetry/opentelemetry-android/pull/887))
- Update Fragment and Activity attribute names. ([#920](https://github.com/open-telemetry/opentelemetry-android/pull/920))

### 🌟 New instrumentation

- Generate events for OkHttp Websocket events
  ([#863](https://github.com/open-telemetry/opentelemetry-android/pull/863))**
- Add build-time `android.util.Log` call-site substitutions
  ([#911](https://github.com/open-telemetry/opentelemetry-android/pull/911))

### 📈 Enhancements

- Support custom attribute extractors to auto-http instrumentations
  ([#867](https://github.com/open-telemetry/opentelemetry-android/pull/867))
- Allow users to configure suppression of some instrumentations.
  ([#883](https://github.com/open-telemetry/opentelemetry-android/pull/883))
- Use event name for crash event (instead of attr)
  ([#894](https://github.com/open-telemetry/opentelemetry-android/pull/894))
- Migrate network change event from zero-duration span to (log-based) event.
  ([#895](https://github.com/open-telemetry/opentelemetry-android/pull/895))

### 🛠️ Bug fixes

- Fix instrumentation publication collisions
  ([#926](https://github.com/open-telemetry/opentelemetry-android/pull/926))

## Version 0.10.0 (2025-03-06)

- This version builds on opentelemetry-java-instrumentation
  [v2.13.3](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.13.3).

### ⚠️⚠️ Breaking changes

- New maven coordinates for http client instrumentations ([#791](https://github.com/open-telemetry/opentelemetry-android/pull/791))
  - `okhttp-3.0-library` -> `instrumentation-okhttp-3.0-library`
  - `okhttp-3.0-agent` -> `instrumentation-okhttp-3.0-agent`
  - `httpurlconnection-library` -> `instrumentation-httpurlconnection-library`
  - `httpurlconnection-agent` -> `instrumentation-httpurlconnection-agent`
- Remove deprecated `exception.escaped` attribute from crash events ([#796](https://github.com/open-telemetry/opentelemetry-android/pull/796))
- `DiskBufferingConfiguration` renamed to `DiskBufferingConfig` ([#753](https://github.com/open-telemetry/opentelemetry-android/pull/753))
- Remove `ServiceManager` instance from `InstallationContext` ([#763](https://github.com/open-telemetry/opentelemetry-android/pull/763))
- Remove hard-coded `exception.escaped` attribute from crashes ([#796](https://github.com/open-telemetry/opentelemetry-android/pull/796))
- Drop support for Kotlin 1.7 ([#869](https://github.com/open-telemetry/opentelemetry-android/pull/869))

### 📈 Enhancements

- The android-agent module now publishes a Bill of Materials (BOM).
  This BOM can be used to coordinate platform dependency versions across the various
  modules contained in opentelemetry-android ([#809](https://github.com/open-telemetry/opentelemetry-android/pull/809))
- Add ability to enable verbose debug for disk buffering config ([#753](https://github.com/open-telemetry/opentelemetry-android/pull/753))
- Ensure current screen attribute is included in logs, when configured ([#785](https://github.com/open-telemetry/opentelemetry-android/pull/785))
- Default max cache size for disk buffering reduced from 60MB to 10MB ([#822](https://github.com/open-telemetry/opentelemetry-android/pull/822))
- Improve concurrency/threading for initialization events ([#836](https://github.com/open-telemetry/opentelemetry-android/pull/836))
- Remove minimum disk buffering cache size requirement and pre-allocation ([#828](https://github.com/open-telemetry/opentelemetry-android/pull/828))
- Add ability to customize the directory used for disk buffering ([#871](https://github.com/open-telemetry/opentelemetry-android/pull/871))

## Version 0.9.0 (2025-01-15)

- This version builds on opentelemetry-java-instrumentation
  [v2.11.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.11.0).

### ⚠️⚠️Breaking changes

A reminder that this project is still alpha and may contain breaking changes
from release-to-release prior to v1.0.0.

- The `AndroidInstrumentation.install()` method signature has changed. Any 3rd-party
  instrumentation written to this interface will need to be updated.
  ([#671](https://github.com/open-telemetry/opentelemetry-android/pull/671))

### 📈 Enhancements

- Add the current screen name to crash events.
  ([#704](https://github.com/open-telemetry/opentelemetry-android/pull/704))
- Add R8 consumer rules.
  ([#685](https://github.com/open-telemetry/opentelemetry-android/pull/685))
- Append the session id attribute to all LogRecords.
  ([#697](https://github.com/open-telemetry/opentelemetry-android/pull/697))
- Add support for wired network types in the network detector.
  ([#673](https://github.com/open-telemetry/opentelemetry-android/pull/673))
- Add ability to generate session start/end events. This feature is currently opt-in.
  ([#717](https://github.com/open-telemetry/opentelemetry-android/pull/717),
   [#719](https://github.com/open-telemetry/opentelemetry-android/pull/719))
- Support newer Android network APIs for API >=29.
  ([#736](https://github.com/open-telemetry/opentelemetry-android/pull/736))

## Version 0.8.0 (2024-10-18)

### 📈 Enhancements

- HttpURLConnection instrumentation migration to AutoService API
  ([#592](https://github.com/open-telemetry/opentelemetry-android/pull/592))
- Make HttpURLConnection connection inactivity timeout configurable and add test for harvester code
  ([#569](https://github.com/open-telemetry/opentelemetry-android/pull/569))
- Expose additional disk buffering configuration
  ([#596](https://github.com/open-telemetry/opentelemetry-android/pull/596))
- Many enhancements to the Android
  [demo-app](https://github.com/open-telemetry/opentelemetry-android/tree/main/demo-app).
  [#545](https://github.com/open-telemetry/opentelemetry-android/pull/545),
  [#554](https://github.com/open-telemetry/opentelemetry-android/pull/554),
  [#568](https://github.com/open-telemetry/opentelemetry-android/pull/568),
  [#570](https://github.com/open-telemetry/opentelemetry-android/pull/570),
  [#577](https://github.com/open-telemetry/opentelemetry-android/pull/577),
  [#584](https://github.com/open-telemetry/opentelemetry-android/pull/584),
  [#598](https://github.com/open-telemetry/opentelemetry-android/pull/598),
  [#604](https://github.com/open-telemetry/opentelemetry-android/pull/604),
  [#605](https://github.com/open-telemetry/opentelemetry-android/pull/605),
  [#627](https://github.com/open-telemetry/opentelemetry-android/pull/627),
  [#634](https://github.com/open-telemetry/opentelemetry-android/pull/634)

### 🛠️ Bug fixes
- Ending "Paused" span for a fragment.
  ([#591](https://github.com/open-telemetry/opentelemetry-android/pull/591))
- start AppStart span when installing activity instrumentation
  ([#578](https://github.com/open-telemetry/opentelemetry-android/pull/578))


## Version 0.7.0 (2024-08-14)

### 🚧 Refactorings

- Implementing an instrumentation API to handle auto instrumentations.
  ([#396](https://github.com/open-telemetry/opentelemetry-android/pull/396)) This change included:
    - The old module `android-agent` was renamed to `core` and a new `android-agent` module was
      created to bring together the core functionalities plus the default instrumentations.
    - The following modules were refactored to implement the new `AndroidInstrumentation` api and to
      invert their dependency with the `core` module so that the `core` isn't aware of
      them: `activity`, `anr`, `crash`, `fragment`, `network`, `slowrendering`, `startup`.
    - (Breaking) The config options related to auto instrumentations that used to live
      in `OtelRumConfig` were move to each instrumentation's `AndroidInstrumentation`
      implementation. This means that the way to configure auto instrumentations now must be done
      via
      the `AndroidInstrumentationLoader.getInstrumentation(AndroidInstrumentationImpl::class.java)`
      method where `AndroidInstrumentationImpl` must be replaced by the implementation type that
      will be configured. Each implementation should contain helper functions (setters, adders, etc)
      to allow configuring itself whenever needed.

### 🌟 New instrumentation

- Http/sURLConnection auto instrumentation.
  ([#133](https://github.com/open-telemetry/opentelemetry-android/pull/133))

### 📈 Enhancements

- Logs are now exported to stdout by
  default. ([#424](https://github.com/open-telemetry/opentelemetry-android/pull/424))
- New method to customize log exporter:
  addLogRecordExporterCustomizer() ([#424](https://github.com/open-telemetry/opentelemetry-android/pull/424))
- Adding RUM initialization
  events. ([#397](https://github.com/open-telemetry/opentelemetry-android/pull/397))
- Upgrading Kotlin to 2.0.0
  ([#388](https://github.com/open-telemetry/opentelemetry-android/pull/388))
- Adding Hanson and Manoel as approvers.
  ([#413](https://github.com/open-telemetry/opentelemetry-android/pull/413))

### 🧰 Tooling

- Not adding artifacts to the GH release page.
  ([#385](https://github.com/open-telemetry/opentelemetry-android/pull/385))
- Populating the session id on screen for the demo app.
  ([#402](https://github.com/open-telemetry/opentelemetry-android/pull/402))
- Setting up docker compose files for the demo app.
  ([#426](https://github.com/open-telemetry/opentelemetry-android/pull/426))
- Running android tests as part of daily checks.
  ([#509](https://github.com/open-telemetry/opentelemetry-android/pull/509))
- Adding a cart to the demo app.
  ([#518](https://github.com/open-telemetry/opentelemetry-android/pull/518))
- Demo app improvements.
  ([#497](https://github.com/open-telemetry/opentelemetry-android/pull/497),
  [#507](https://github.com/open-telemetry/opentelemetry-android/pull/507),
  [#414](https://github.com/open-telemetry/opentelemetry-android/pull/414))

## Version 0.6.0 (2024-05-22)

This version of OpenTelemetry Android is built on:

* OpenTelemetry Java
  Instrumentation [2.4.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.4.0)
* OpenTelemetry Java
  Contrib [1.34.0-alpha](https://github.com/open-telemetry/opentelemetry-java-contrib/releases/tag/v1.35.0)
* OpenTelemetry
  SDK [1.38.0](https://github.com/open-telemetry/opentelemetry-java/releases/tag/v1.38.0)

### 🌟 New instrumentation

- Experimental Volley http client
  instrumentation [#291](https://github.com/open-telemetry/opentelemetry-android/pull/291).

### 📈 Enhancements

- There is now an initial version of an OpenTelemetry Android demo
  app. [#338](https://github.com/open-telemetry/opentelemetry-android/pull/338)
- Session timeout duration is now configurable beyond the 15 minute
  default [#330](https://github.com/open-telemetry/opentelemetry-android/pull/330)

### 🛠️ Bug fixes

- Scheduled components now use fixed delay instead of fixed
  rate [#332](https://github.com/open-telemetry/opentelemetry-android/pull/332).

### 🧰 Tooling

- A variety of small tweaks to the build process to make it smoother and more consistent with other
  OpenTelemetry Java repos.

## Version 0.5.0 (2024-04-23)

⚠️⚠️⚠️ There are considerable breaking changes in this release.

Breaking changes include considerable restructuring of the overall project layout. This provides a
much more modularized project that publishes more granular instrumentation modules. Note that as a
result of this, the topmost dependency is changing its name
to `io.opentelemetry.android:android-agent`.

### 📈 Enhancements

- Append global attributes to logs signal.
  ([#266](https://github.com/open-telemetry/opentelemetry-android/pull/266))
- Change crash reporting to send a LogRecord instead of Span.
  ([#237](https://github.com/open-telemetry/opentelemetry-android/pull/237))
- Restructure
  modules ([#267](https://github.com/open-telemetry/opentelemetry-android/pull/267), [#269](https://github.com/open-telemetry/opentelemetry-android/pull/269),
  and [#276](https://github.com/open-telemetry/opentelemetry-android/pull/276))
- Update upstream deps
  ([#301](https://github.com/open-telemetry/opentelemetry-android/pull/301)
  and [#304](https://github.com/open-telemetry/opentelemetry-android/pull/304))
- Update README re: desugaring
  ([#309](https://github.com/open-telemetry/opentelemetry-android/pull/309))

### 🛠️ Bug fixes

- Ensure that services are initialized via ServiceManager when `OpenTelemetryRum` is built.
  ([#272](https://github.com/open-telemetry/opentelemetry-android/pull/272))
- Start the `ServiceManager` itself when `OpenTelemetryRum` is built.
  ([#278](https://github.com/open-telemetry/opentelemetry-android/pull/278))

### 🧰 Tooling

- Update Release process
  ([#300](https://github.com/open-telemetry/opentelemetry-android/pull/300))
- Adding '-alpha' to all modules' versions
  ([#297](https://github.com/open-telemetry/opentelemetry-android/pull/297))

## Version 0.4.0 (2024-03-04)

- Update
  to [opentelemetry-java-instrumentation 1.32.1](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.32.1)
- Update
  to [opentelemetry-java sdk 1.35.0](https://github.com/open-telemetry/opentelemetry-java/releases/tag/v1.35.0)
- Wire up support for ANRs, crash reporting, and slow rendering detection, with configurability
  support (#192)
- Fix okhttp instrumentation to include known http methods (#215)
- Finish adding initial implementation of through-disk buffering support (#194, #221)

## Version 0.3.0 (2023-12-13)

- Update
  to [opentelemetry-java-instrumentation 1.32.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.32.0)
- Update
  to [opentelemetry-java sdk 1.33.0](https://github.com/open-telemetry/opentelemetry-java/releases/tag/v1.33.0)
- Stabilizing support for okhttp automatic build-time instrumentation (#159)

## Version 0.2.0 (2023-10-20)

This is a regular monthly cadence release, which follows the releases of
opentelemetry-java-instrumentation and opentelemetry-java (core/sdk).

- Update
  to [opentelemetry-java-instrumentation 1.31.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v1.31.0)
- Update
  to [opentelemetry-java sdk 1.31.0](https://github.com/open-telemetry/opentelemetry-java/releases/tag/v1.31.0)
- BREAKING - Update to latest java semantic conventions (#114)
    - `net.host.connection.type` -> `network.connection.type`
    - `net.host.carrier.icc` -> `network.carrier.icc`
    - `net.host.carrier.mcc` -> `network.carrier.mcc`
    - `net.host.carrier.mnc` -> `network.carrier.mnc`
    - `net.host.carrier.name` -> `network.carrier.name`
    - `net.host.connection.type` -> `network.connection.type`
    - `net.host.connection.subtype` -> `network.connection.subtype`
- Add experimental support for okhttp automatic build-time instrumentation (#64, #110)

## Version 0.1.0 (2023-09-13)

This version marks the first baseline release of `opentelemetry-android` instrumentation.
This project is classified as experimental.

## 📈 Enhancements

* Update to upstream otel sdk 1.29.0 (#75)
* Add `OpenTelemetryRumBuilder.addPropagatorCustomizer()` to allow user to customize trace
  propagation (#71)
