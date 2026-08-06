# Glide Image-Loading Instrumentation

Status: development

The Glide instrumentation automatically generates OpenTelemetry `image.load` spans for every
image request made through [Glide](https://github.com/bumptech/glide). It captures the image
source, load outcome, and a sanitised URL without requiring any change to existing image-loading
call sites.

Image URLs are automatically sanitised before recording: everything after the first `?` or `#`
is stripped and the value is truncated to 512 characters, so authentication tokens, signed
parameters, and other sensitive query-string data never reach the telemetry back-end — a hard
requirement for BFSI and banking applications. Exception messages recorded on failure are
scrubbed the same way (embedded query strings removed) because `GlideException` root causes
routinely embed the full request URL in their message.

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
    * `image.load.status` — `"success"`, `"error"`, or `"cancelled"` (request cancelled while
      the fetch was in flight, e.g. scroll-away — span status stays UNSET since a cancellation
      is not an error)
    * `image.is_first_resource` — `true` if this was the first resource loaded for the target

`image.url` is only recorded for URL-like models (`String`, `Uri`, `URL`, `GlideUrl`). Other
model types — `File`, `byte[]`, resource IDs, custom model classes — are identified by
`image.model_type` alone, because an arbitrary model's `toString()` may embed customer data
(PII) or, for base64 `data:` URI strings, an enormous payload.

On failure, the span status is set to `ERROR` and a sanitised `exception` event is recorded on
the span (exception class name plus a query-scrubbed message). `span.recordException` is
deliberately **not** used: `GlideException` aggregates root causes whose raw messages typically
contain the unsanitised URL, tokens included.

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

### Concurrent requests sharing the same model instance

In-flight spans are keyed by the **identity** of the Glide model object. Two *different* String
instances with the same URL content are tracked independently, but two concurrent requests that
share the *same* model instance (a URL held in a constant, or the same String field bound to two
targets at once — e.g. the same avatar in a toolbar and a list row) collide: the second request
ends the first request's span as stale, and the terminal callbacks race for the single entry, so
durations/outcomes can be attributed to the wrong request. This cannot crash or leak — worst case
is inaccurate telemetry for those overlapping loads. If this pattern is hot in your app, pass a
fresh model instance per request.

### Cancellation coverage

Glide's `RequestListener` has no cancellation callback. Cancellations that occur while the fetch
is in flight are handled by `OtelContextDataFetcher.cancel()` (span ends with
`image.load.status = "cancelled"`). Cancellations before the fetcher is built or after the fetch
(during decode) have no hook; those spans are reclaimed by the span store's size cap (200
entries) rather than ending at the exact cancellation time.

### Initialisation order

`GlideOtelModule` registers the OTel model loaders when Glide initialises its component registry
— which happens once, on first use of Glide. The OpenTelemetry RUM SDK must therefore be
initialised **before** the first Glide request, or the network-path instrumentation is silently
skipped for the process lifetime (only memory-cache synthetic spans would appear). With the
Gradle plugin's auto-init (ContentProvider, runs before `Application.onCreate`) this is always
satisfied; with manual/delayed SDK init, initialise the SDK before any image loads.

### One listener registration style per request

Register `VunetGlideRequestListener` **either** globally (`AppGlideModule.applyOptions`) **or**
per-request (Compose `GlideImage` `requestBuilderTransform`) — never both for the same request.
Both listeners would fire for the same request and memory-cache hits would be double-counted
(the second invocation finds no stored span and synthesises a duplicate).

### Timestamp precision

The `image.load` span start time is set from `System.currentTimeMillis() * 1_000_000`, i.e.
**millisecond** wall-clock resolution rescaled to nanoseconds — not true nanosecond precision.
Sub-millisecond operations (notably memory-cache hits) may therefore report a near-zero duration in
the backend. This is an accepted tradeoff for RUM; finer resolution would require pairing a
`System.nanoTime()` delta with a clock offset (a pattern used elsewhere in the SDK). Additionally,
because the start timestamp comes from the wall clock while the end comes from the SDK clock, an
NTP clock adjustment mid-request can skew a span's reported duration.
