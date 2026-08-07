# Coil Image-Loading Instrumentation

Status: development

The Coil instrumentation automatically generates OpenTelemetry `image.load` spans for every
image request made through [Coil](https://coil-kt.github.io/coil/) (version 2.x). It captures
the image source, load outcome, and a sanitised URL without requiring any change to existing
image-loading call sites.

Image URLs are automatically sanitised before recording: everything after the first `?` is
stripped, so authentication tokens, signed parameters, and other sensitive query-string data
never reach the telemetry back-end — a hard requirement for BFSI and banking applications.

This instrumentation is **not** included in the `android-agent` by default and must be
declared as an explicit dependency.

## Telemetry

Data produced by this instrumentation uses instrumentation scope name
`io.opentelemetry.android.instrumentation.coil`.

### Image Load

* Type: Span
* Name: `image.load`
* Description: A span that covers the full lifecycle of a single Coil image request, from
  the moment Coil enqueues the request to the point where the drawable is delivered or the
  request fails.
* Attributes:
    * `image.url` — sanitised image URL (query parameters stripped)
    * `image.model_type` — fully-qualified class name of the Coil data model (e.g. `java.lang.String`)
    * `image.source` — where Coil resolved the image from:
        * `"network"` — fetched from the network (OkHttp child spans will appear under this span)
        * `"disk"` — served from Coil's disk cache
        * `"memory"` — served from Coil's memory cache or in-memory bitmap pool
    * `image.load.status` — `"success"`, `"error"`, or `"cancelled"`
    * `image.target.view_id` — resource entry name of the view the image is loading into
      (e.g. `avatar_image`); `"no-id"` when the view has no `android:id`, and `"unresolved"` when
      the id has no resource-table entry (a runtime `View.generateViewId()` value, whose raw
      integer restarts each process launch and so is never emitted). Absent for requests
      without a `ViewTarget` — notably Compose `AsyncImage` and preloads
    * `image.target.view_type` — fully-qualified class of that view
      (e.g. `androidx.appcompat.widget.AppCompatImageView`)
    * `image.error.type` — failure class, on error spans only (see below)

Combined with the `screen.name` attribute that the SDK appends to every span, the view attributes
identify exactly **which** `ImageView` on a screen failed — `screen.name` alone cannot separate two
images rendered on the same screen.

On failure, the span status is set to `ERROR` and the throwable is recorded on the span
via `span.recordException`. Because `recordException` writes a span *event* — which most back-ends
cannot group or chart — the throwable's class is **also** recorded as the `image.error.type`
attribute, so failure breakdowns (timeouts vs. HTTP errors vs. decode errors) can be charted
directly.

> [!NOTE]
> Loads abandoned before they finish — fast scrolling, `DisposableEffect` cleanup, navigating away —
> arrive in `onCancel` and are recorded as `image.load.status = "cancelled"` with an **UNSET** span
> status. They are deliberately not errors, so exclude or include them explicitly when building a
> failure-rate chart.

### OkHttp child spans

When an image is fetched over the network, the OkHttp instrumentation automatically creates
child `GET` spans under the `image.load` span. This works because `VunetCoilInterceptor` wraps
the downstream chain execution in `withContext(span.asContextElement())`, propagating the
`image.load` span into the coroutine context before OkHttp executes. Both the interceptor
and the event listener factory must be registered (see Installation below).

## Installation

Two one-time steps for a customer app: declare the dependencies, then register the Coil hooks
once in `Application.onCreate`. After that **every** image load — whether from a View
`imageView.load(url)` or a Compose `AsyncImage(...)` — is traced automatically with no
per-call-site changes.

The telemetry SDK itself is started for you: when the `vunet.telemetry.android` Gradle plugin is
applied with `autoInitialize = true`, `VunetAutoInitProvider` boots the OpenTelemetry RUM SDK at
process start and calls `CoilInstrumentation.install()`, which provisions the tracer the hooks
below read from. You do **not** call `OpenTelemetryRumInitializer.initialize` yourself.

### 1. Add the dependencies

The OTel Coil instrumentation is `compileOnly` against Coil, so add **both** the Coil library and
the instrumentation artifact to your app module:

```kotlin
dependencies {
    implementation(libs.coil)              // coil-kt 2.x (your app already uses this)
    implementation(libs.coil.compose)      // only if you use AsyncImage

    // OTel Coil instrumentation (pulled in transitively by the Vunet SDK, or add it directly):
    implementation("com.vunetsystems.opentelemetry.android.instrumentation:coil:0.0.1-SNAPSHOT")
}
```

### 2. Register the Coil hooks once in Application.onCreate

Register both `VunetCoilEventListenerFactory` (span lifecycle) and `VunetCoilInterceptor`
(OkHttp context propagation) on a single `ImageLoader`, then publish it as Coil's singleton so the
top-level extensions and Compose `AsyncImage` all use it:

```kotlin
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .eventListenerFactory(VunetCoilEventListenerFactory())
                .components {
                    add(VunetCoilInterceptor())
                }
                .build(),
        )
    }
}
```

### 3. Use Coil as usual — no call-site changes

Once the singleton loader is set, existing code is traced with no modification, in both UI styles:

```kotlin
// View / XML
imageView.load("https://example.com/avatar.png")

// Jetpack Compose (coil-compose) — covered automatically by the singleton loader
AsyncImage(model = "https://example.com/avatar.png", contentDescription = null)
```

Each of these now emits an `image.load` span, with OkHttp child spans on the network path.

> [!NOTE]
> Both `VunetCoilEventListenerFactory` **and** `VunetCoilInterceptor` must be registered.
> The factory manages the span lifecycle; the interceptor propagates the span context into
> the coroutine so OkHttp child spans are correctly parented.

> [!NOTE]
> If you construct `ImageLoader` instances manually rather than using the singleton, register
> both on each builder individually.

> [!NOTE]
> `VunetCoilEventListenerFactory` returns `EventListener.NONE` (zero overhead) when
> `CoilInstrumentation` has not yet been installed by the OpenTelemetry RUM SDK, so it is
> safe to register it unconditionally during application startup.

## How it works

| Phase | Class | Action |
|---|---|---|
| Request enqueued (main dispatcher) | `CoilOtelEventListener.onStart` | Starts span, records URL and target view, stores in `CoilSpanStore` |
| Fetch runs (coroutine dispatcher) | `VunetCoilInterceptor` | Restores span via `withContext(span.asContextElement())` → OkHttp spans parent to image.load |
| Request finished | `CoilOtelEventListener.onSuccess` / `onError` / `onCancel` | Adds outcome attributes, ends span |

## Disabling via the agent DSL

If you need to turn off Coil instrumentation without removing the dependency:

```kotlin
OpenTelemetryRumInitializer.initialize(application) {
    instrumentations {
        coil {
            enabled(false)
        }
    }
}
```
