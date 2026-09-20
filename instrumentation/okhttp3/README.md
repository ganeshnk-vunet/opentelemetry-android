# Android Instrumentation for OkHttp version 3.0 and higher

Status: development

The OpenTelemetry OkHttp instrumentation for Android instruments client-side requests
made via OkHttp (version 3.0 +) [okhttp3](https://square.github.io/okhttp/). It adds distributed tracing context,
creates client HTTP spans, and records request/response metadata.

## Telemetry

This instrumentation produces HTTP client spans using the OpenTelemetry [HTTP semantic
conventions](https://opentelemetry.io/docs/specs/semconv/http/http-spans/). The span name is created via the HTTP span name extractor and attributes
are provided by the OkHttp attributes getter.

### HTTP client span

* Type: Span
* Name: Determined by the HTTP span name extractor (typically `HTTP {method}` or derived from the URL)
* Description: Client-side HTTP request
* Common attributes (following OpenTelemetry HTTP semantic conventions):
  * `http.request.method` — request method (GET, POST, etc.)
  * `url.full` — full request URL
  * `http.response.status_code` — response status code
  * `network.protocol.version` — protocol version (e.g. `1.1`, `2` for HTTP/2)
  * `server.address` — logical server host
  * `server.port` — logical server port
  * `network.peer.address` / `network.peer.port` — connection endpoint, when available
  * Captured request/response headers per configuration (`http.request.header.<name>` / `http.response.header.<name>`)
  * Network phase timings (incubating, OkHttp only when enabled): `http.client.timing.dns_ms`, `connect_ms`, `tls_ms`, `ttfb_ms`, `download_ms`, `total_ms`, `abandoned`, and related span events (`http.dns`, `http.connect`, `http.secure_connect`, `http.ttfb`, `http.download`, `http.call`)

If a request fails, the span is ended and the error is recorded. Failed spans include a normalized
`http.error.category` attribute alongside the standard `error.type` attribute:

| `http.error.category` | When |
|-----------------------|------|
| `timeout` | Connect/read/write timeout (`SocketTimeoutException`, `InterruptedIOException`) |
| `dns` | DNS resolution failure (`UnknownHostException`) |
| `ssl` | TLS or certificate failure (`SSLException`, `CertificateException`) |
| `io` | Other transport I/O failure (`IOException`) |
| `http_client` | HTTP 4xx/5xx response with no transport exception |
| `unknown` | Other failure types |

Use `http.response.status_code` to distinguish 4xx vs 5xx when `http.error.category` is `http_client`.

#### Status code when no response was received

When a request fails before any response arrives, the OpenTelemetry HTTP attributes extractor omits
`http.response.status_code` entirely, which is indistinguishable downstream from a request that was
never instrumented. This instrumentation fills that gap:

| Failure | Exception | `http.response.status_code` |
|---------|-----------|-----------------------------|
| DNS resolution failure | `UnknownHostException` | `0` |
| Connection refused / no route | `ConnectException`, `NoRouteToHostException` | `0` |
| TLS handshake failure | `SSLHandshakeException` (including a wrapped `CertificateException`) | `0` |
| Timeout | `SocketTimeoutException`, `InterruptedIOException` | *absent* |
| Cancelled call (OkHttp `Call.cancel()`) | `IOException("Canceled")` | *absent* |
| Failure after the server answered | `SocketException("Connection reset")` mid-body, `StreamResetException`, mid-stream `SSLException` | actual status (`200`, `500`, …) |
| Any other failure | — | *absent* |
| Response received (any status) | — | actual status (`200`, `404`, `500`, …) |

Reporting `0` asserts "the request never reached the server", so the match is on the specific
exception types that prove it — **not** on the coarse `http.error.category` buckets above. The `io`
category maps *any* unmatched `IOException`, which includes failures proving the opposite: an HTTP/2
`StreamResetException` means the server sent the reset. Everything outside the pre-request set keeps
the attribute absent:

* **Timeouts** — the request may well have reached the server and been processed; the response
  simply did not arrive in time.
* **Cancelled calls** — cancellation is routine on mobile (list scrolling, rapid navigation, a
  cancelled coroutine scope) and can land after the server was reached. OkHttp raises it as a plain
  `IOException("Canceled")`, so the instrumentation checks `Call.isCanceled()` directly rather than
  inferring it from the exception type.
* **Failures after the server answered** — `0` would be a lie, and so would *absent*. On the
  default `captureNetworkTimingPhases` path the span is ended by
  `OkHttpCallCompletionCoordinator`, which now reports the response it already recorded *alongside*
  the error, so a 200 that fails mid-body keeps its status code. This also makes the two timing
  configurations agree: previously the response was discarded whenever an error was present, while
  the plain `TracingInterceptor` used when phases are off recorded the real code.
* **Unrecognised failures** — by definition it is not known whether the server was reached, which
  is exactly where claiming "never got there" is least defensible.

> **Note:** reporting `0` is a deliberate deviation from the OpenTelemetry semantic conventions,
> which leave `http.response.status_code` unset when no response was received.

### Network phase timing (incubating)

When `captureNetworkTimingPhases` is enabled (default `true`), OkHttp `EventListener` callbacks capture per-request phase durations as both span attributes (`http.client.timing.*`) and span events (`http.*` with `duration_ms`). Attribute names follow incubating OpenTelemetry client timing conventions and may change.

Disable timing capture:

```java
OkHttpInstrumentation instrumentation = AndroidInstrumentationLoader.getInstrumentation(OkHttpInstrumentation.class);
instrumentation.setCaptureNetworkTimingPhases(false);
```

> [!NOTE]
> Phase breakdown requires OkHttp Byte Buddy instrumentation (`okhttp3-agent`). `download_ms` may be absent when the response body is not consumed before the span ends.

### Span completion and the call duration cap

With phase timing enabled the span is ended from OkHttp's `EventListener`, which reports a call as
finished when its response body reaches EOF or is closed, whichever comes first. A body that is read
to completion therefore ends its span promptly, but one that is never read — a server-sent-event
stream, a long poll, or a response the caller simply drops — reports nothing until it is closed, and
the span would otherwise stretch across the whole of that wait.

A watchdog bounds this. Any span still open after the cap is ended anyway and carries
`http.client.timing.abandoned = true` alongside `http.client.timing.phases_complete = false`, so a
truncated duration is always identifiable and never silently mistaken for a slow request. The cap
defaults to **5 minutes**; a client that sets OkHttp's own `callTimeout` is held to that instead,
since the application has already stated the longest call it considers legitimate.

> [!IMPORTANT]
> **The cap is not a definition of "too slow".** Every call that finishes inside it reports its true
> duration, however slow — a request that genuinely takes two minutes is recorded as two minutes.
> The cap only applies to calls that never report completion at all, and it is deliberately set
> above any plausible real request so that slow requests, which are the ones worth investigating,
> are never deleted by it.
>
> The budget is applied **per phase**. A network interceptor sees the response once its *headers*
> arrive, which separates two failures with nothing in common: before it the app is waiting on the
> server, after it the app is reading — or failing to read — the body. Each phase gets the full
> budget from its own start, so a slow server and a slow download are both measured in full instead
> of sharing one clock and truncating whichever happens second. Both phases stay bounded, so a call
> that never receives headers is still cut off rather than pending forever — which matters because
> OkHttp's `callTimeout` defaults to disabled and `readTimeout` is per-read, so a trickling server
> trips neither.
>
> Read `abandoned = true` as *"ran at least this long, exact duration unknown"* and exclude those
> rows from latency percentiles rather than treating them as measurements. Lowering the cap below
> the slowest request you care about will silently remove real findings — that is the failure mode
> to avoid when tuning it.

```java
OkHttpInstrumentation instrumentation = AndroidInstrumentationLoader.getInstrumentation(OkHttpInstrumentation.class);
instrumentation.setMaxCallDurationMillis(120_000L);
```

## Quickstart

### Add these dependencies to your project

Replace `BYTEBUDDY_VERSION` with the [latest
release](https://central.sonatype.com/artifact/net.bytebuddy/byte-buddy-gradle-plugin/versions).

#### Byte buddy compilation plugin

This plugin leverages
Android's [Transform API](https://developer.android.com/reference/tools/gradle-api/current/com/android/build/api/variant/ScopedArtifactsOperation#toTransform(com.android.build.api.artifact.ScopedArtifact,kotlin.Function1,kotlin.Function1,kotlin.Function1))
to instrument bytecode at compile time. You can find more info on
its [repo page](https://github.com/raphw/byte-buddy/tree/master/byte-buddy-gradle-plugin/android-plugin).

```groovy
plugins {
    id 'net.bytebuddy.byte-buddy-gradle-plugin' version 'BYTEBUDDY_VERSION'
}
```

#### Project dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:okhttp3-library:1.3.0-alpha")
byteBuddy("io.opentelemetry.android.instrumentation:okhttp3-agent:1.3.0-alpha")
```

After adding the plugin and the dependencies to your project, your OkHttp requests will be traced
automatically.

### Configuration

You can configure the automatic instrumentation by using the setters
from
the [OkHttpInstrumentation](library/src/main/java/io/opentelemetry/instrumentation/library/okhttp/OkHttpInstrumentation.kt)
instance provided via the AndroidInstrumentationLoader as shown below:

```java
OkHttpInstrumentation instrumentation = AndroidInstrumentationLoader.getInstrumentation(OkHttpInstrumentation.class);

// Call `instrumentation` setters.
```

> [!NOTE]
> You must make sure to apply any configurations **before** initializing your OpenTelemetryRum
> instance (i.e. calling OpenTelemetryRum.builder()...build()). Otherwise your configs won't be
> taken into account during the RUM initialization process.
