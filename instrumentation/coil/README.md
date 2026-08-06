# Coil Image-Loading Instrumentation

Status: development

The Coil instrumentation automatically generates OpenTelemetry `image.load` spans for every
image request made through [Coil](https://coil-kt.github.io/coil/) (version 2.x). It captures
the image source, load outcome, and a sanitised URL without requiring any change to existing
image-loading call sites.

Image URLs are automatically sanitised before recording: everything after the first `?` or `#`
is stripped and the value is truncated to 512 characters, so authentication tokens, signed
parameters, and other sensitive query-string data never reach the telemetry back-end — a hard
requirement for BFSI and banking applications. Exception messages recorded on failure are
scrubbed the same way (embedded query strings removed) because HTTP-stack exceptions routinely
embed the full request URL in their message.

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
    * `image.load.status` — `"success"`, `"error"`, or `"cancelled"` (request disposed before
      completion, e.g. fast scrolling — span status stays UNSET since a cancellation is not an
      error)

On failure, the span status is set to `ERROR` and a sanitised `exception` event is recorded on
the span (exception class name plus a query-scrubbed message). `span.recordException` is
deliberately **not** used: it would serialise the raw messages of the entire cause chain, which
for HTTP failures typically contain the unsanitised URL, tokens included.

### OkHttp child spans

When an image is fetched over the network, the OkHttp instrumentation automatically creates
child `GET` spans under the `image.load` span. This works because `VunetCoilInterceptor` wraps
the downstream chain execution in `withContext(span.asContextElement())`, propagating the
`image.load` span into the coroutine context before OkHttp executes. Both the interceptor
and the event listener factory must be registered (see Installation below).

> [!IMPORTANT]
> `VunetCoilInterceptor` looks up the span by the **identity** of the incoming request. If
> another interceptor rebuilds the request (`request.newBuilder().build()`) before this one
> runs, the lookup misses and OkHttp spans silently lose their parent. Register
> `VunetCoilInterceptor` **first** in the `components { }` block, ahead of any other
> interceptors.

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
| Request enqueued (any thread) | `CoilOtelEventListener.onStart` | Starts span, calls `makeCurrent()`, stores in `CoilSpanStore` |
| Fetch runs (coroutine dispatcher) | `VunetCoilInterceptor` | Restores span via `withContext(span.asContextElement())` → OkHttp spans parent to image.load |
| Request finished | `CoilOtelEventListener.onSuccess` / `onError` | Closes scope, adds attributes, ends span |

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
