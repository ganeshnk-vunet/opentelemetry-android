# Glide Image-Loading Instrumentation

Status: development

The Glide instrumentation automatically generates OpenTelemetry `image.load` spans for every
image request made through [Glide](https://github.com/bumptech/glide). It captures the image
source, load outcome, and a sanitised URL without requiring any change to existing image-loading
call sites.

Image URLs are automatically sanitised before recording: everything after the first `?` is
stripped, so authentication tokens, signed parameters, and other sensitive query-string data
never reach the telemetry back-end — a hard requirement for BFSI and banking applications.

This instrumentation is **not** included in the `android-agent` by default and must be
declared as an explicit dependency.

## Telemetry

Data produced by this instrumentation uses instrumentation scope name
`io.opentelemetry.android.instrumentation.glide`.

### Image Load

* Type: Span
* Name: `image.load`
* Description: A span that covers the full lifecycle of a single Glide image request, from
  the moment Glide begins resolving the model to the point where the resource is delivered
  or the request fails.
* Attributes:
    * `image.url` — sanitised image URL (query parameters stripped)
    * `image.model_type` — fully-qualified class name of the Glide model (e.g. `java.lang.String`)
    * `image.source` — where Glide resolved the image from:
        * `"network"` — fetched from the network (OkHttp child spans will appear under this span)
        * `"disk_cache"` — served from Glide's data or resource disk cache
        * `"disk"` — served from Glide's local disk (e.g. file URI)
        * `"memory"` — served from Glide's active-resources or memory LRU cache
    * `image.load.status` — `"success"` or `"error"`
    * `image.is_first_resource` — `true` if this was the first resource loaded for the target
    * `image.target.view_id` — resource entry name of the view the image is loading into
      (e.g. `avatar_image`); `"no-id"` when the view has no `android:id`, and `"unresolved"` when
      the id has no resource-table entry (a runtime `View.generateViewId()` value, whose raw
      integer restarts each process launch and so is never emitted). Absent for non-view
      targets (`submit()`, `preload()`, custom `Target`s)
    * `image.target.view_type` — fully-qualified class of that view
      (e.g. `androidx.appcompat.widget.AppCompatImageView`)
    * `image.error.type` — failure class, on error spans only (see below)

Combined with the `screen.name` attribute that the SDK appends to every span, the view attributes
identify exactly **which** `ImageView` on a screen failed — `screen.name` alone cannot separate two
images rendered on the same screen.

On failure, the span status is set to `ERROR` and the `GlideException` is recorded on the
span via `span.recordException`. Because `recordException` writes a span *event* — which most
back-ends cannot group or chart — the failure class is **also** recorded as the
`image.error.type` attribute. Glide wraps every failure in a generic `GlideException`, so the
value is taken from the first root cause (`SocketTimeoutException`, `HttpException`,
`FileNotFoundException`, …) and falls back to `GlideException` only when Glide recorded no
root cause.

Failures are never silently dropped: if no span was pre-created — a memory-cache path, an
uncovered model type, or a null model such as `load(null)` — one is synthesised in
`onLoadFailed`. Spans for a null model record `image.url` and `image.model_type` as `"unknown"`.

### OkHttp child spans

When an image is fetched over the network, the OkHttp instrumentation automatically creates
child `GET` spans under the `image.load` span. This works because `OtelContextDataFetcher`
restores the `image.load` span context on Glide's background thread before OkHttp executes.
No additional configuration is required.

## Installation

The wiring differs by UI style. **View / XML apps** (`Glide.with(...).load(...).into(...)`)
register the listener once via an `AppGlideModule`. **Jetpack Compose apps** that use the
`GlideImage` composable register the listener inline on each call — no `AppGlideModule` and no
`kapt` are required. Pick the section that matches your app.

The telemetry SDK itself is started for you: when the `vunet.telemetry.android` Gradle plugin is
applied with `autoInitialize = true`, `VunetAutoInitProvider` boots the OpenTelemetry RUM SDK at
process start and calls `GlideInstrumentation.install()`, which provisions the tracer the hooks
below read from. You do **not** call `OpenTelemetryRumInitializer.initialize` yourself.

### 1. Add the dependencies

The OTel Glide instrumentation is `compileOnly` against Glide, so add the Glide library yourself
alongside the instrumentation artifact:

```kotlin
plugins {
    id("kotlin-kapt") // View/XML apps only — needed to generate the AppGlideModule glue
}

dependencies {
    implementation(libs.glide)
    implementation(libs.glide.okhttp)      // OkHttp integration → enables network child spans
    kapt(libs.glide.compiler)              // View/XML apps only (processes @GlideModule)
    implementation(libs.glide.compose)     // Compose apps only — the GlideImage composable

    // OTel Glide instrumentation (pulled in transitively by the Vunet SDK, or add it directly):
    implementation("com.vunetsystems.opentelemetry.android.instrumentation:glide:0.0.1-SNAPSHOT")
}
```

### 2a. View / XML apps — register once in your AppGlideModule

Register `VunetGlideRequestListener` globally so it receives the terminal callbacks for every
request and ends the span:

```kotlin
@GlideModule
class SampleAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.addGlobalRequestListener(VunetGlideRequestListener())
    }

    // Optional: GlideOtelModule (a LibraryGlideModule) already injects the OTel model loaders
    // automatically at startup. Override registerComponents only if you want to be explicit
    // (e.g. a custom GlideBuilder that bypasses LibraryGlideModule auto-discovery):
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        GlideInstrumentation.registerGlideComponents(registry)
    }
}
```

After this one-time setup, all `Glide.with(...).load(...).into(...)` calls are traced
automatically with no further code changes.

> [!NOTE]
> `VunetGlideRequestListener` must be registered **before** Glide is initialised.
> Registering it inside `applyOptions` is the correct and guaranteed-safe location.

> [!NOTE]
> `GlideOtelModule` is a `LibraryGlideModule` that Glide discovers automatically at startup.
> It injects the `OtelContextModelLoader` into Glide's component registry, so overriding
> `registerComponents()` yourself is optional in the standard setup.

### 2b. Jetpack Compose apps — register inline on each GlideImage

The `GlideImage` composable does not go through your `AppGlideModule`, so register the listener
inline via the `requestBuilderTransform` lambda. No `AppGlideModule` or `kapt` is needed:

```kotlin
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun Avatar(url: String) {
    GlideImage(
        model = url,
        contentDescription = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        it.listener(
            VunetGlideRequestListener()
                as RequestListener<android.graphics.drawable.Drawable>,
        )
    }
}
```

`GlideOtelModule` is still auto-discovered, so network child-span parenting works the same way;
only the terminal listener has to be attached per call in Compose.

## How it works

| Phase | Class | Action |
|---|---|---|
| Request started (main thread) | `OtelContextModelLoader` | Starts span, captures OTel context |
| Fetch runs (background thread) | `OtelContextDataFetcher` | Restores context → OkHttp spans parent to image.load |
| Request finished | `VunetGlideRequestListener` | Adds attributes, ends span |
| Memory cache hit | `VunetGlideRequestListener` | Synthesises a span (Glide bypasses ModelLoader for memory hits) |

## Expected test sequence

To verify each `image.source` value in order:

1. Fresh install (or clear app data) → press Network button → `image.source = "network"`, OkHttp child spans visible
2. Press Disk Cache button (skipMemoryCache=true) → `image.source = "disk_cache"`
3. Press Memory Cache button (DiskCacheStrategy.NONE) → `image.source = "memory"`

If the Network button shows `disk_cache` instead of `network`, Glide's disk cache is already
populated from a prior session. Clear app storage via **Settings → Apps → [App] → Clear Storage**
and retry.

## Known limitations

### Model-type coverage

The instrumentation only intercepts the network-facing model types that Glide resolves to an
`InputStream`: `String`, `GlideUrl`, `java.net.URL`, and `android.net.Uri`. Loads from other model
types — `File`, `byte[]`, `Drawable`/resource IDs, or custom registered models — do **not** pass
through `OtelContextModelLoader`, so they will:

- not produce a network-path `image.load` span with OkHttp child-span parenting, and
- for memory-cache hits, still produce a synthesised span via `VunetGlideRequestListener`
  (since that path keys off the request listener, not the model loader).

In practice BFSI apps load remote images via URL/`String`/`Uri`, which are all covered. If your app
relies heavily on `File` or resource-ID loads and you need spans for them, additional model types
would have to be registered in `GlideInstrumentation.registerGlideComponents`.

### Timestamp precision

The `image.load` span start time is set from `System.currentTimeMillis() * 1_000_000`, i.e.
**millisecond** wall-clock resolution rescaled to nanoseconds — not true nanosecond precision.
Sub-millisecond operations (notably memory-cache hits) may therefore report a near-zero duration in
the backend. This is an accepted tradeoff for RUM; finer resolution would require pairing a
`System.nanoTime()` delta with a clock offset (a pattern used elsewhere in the SDK).
