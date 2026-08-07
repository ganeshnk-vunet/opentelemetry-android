# Concurrency Context Propagation Instrumentation

Status: development

This instrumentation automatically propagates OpenTelemetry `Context` across common Android
concurrency boundaries so child spans (for example HTTP client spans) retain correct parent-child
linkage when work leaves the calling thread.

## Problem

OpenTelemetry context is thread-local. Without propagation, patterns such as
`withContext(Dispatchers.IO)`, `executor.execute { }`, and `handler.post { }` lose the active span
context and downstream instrumentation sees `Context.root()`.

## What is instrumented

| Boundary | Mechanism |
|----------|-----------|
| Kotlin coroutines | Weaves `CoroutineContextKt.newCoroutineContext` to add `Context.asContextElement()` |
| `ThreadPoolExecutor.execute` | Wraps submitted `Runnable` with captured context |
| `Handler.post*` | Wraps posted `Runnable` with captured context |

HTTP, logging, and other instrumentations that read `Context.current()` benefit automatically once
context reaches the worker thread.

## Installation

### Byte Buddy plugin (required)

```kotlin
plugins {
    id("net.bytebuddy.byte-buddy-gradle-plugin") version "BYTEBUDDY_VERSION"
}
```

### Dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:concurrency-library:VERSION")
byteBuddy("io.opentelemetry.android.instrumentation:concurrency-agent:VERSION")
```

When using the VuNet Gradle plugin (`vunet.telemetry.android`), wire the agent the same way as
`startup-agent` and `okhttp3-agent`.

`concurrency-library` is included in `android-agent` so runtime advice classes resolve after
weaving. The **agent** must still be applied on the application module.

## Known limitations

- Requires compile-time Byte Buddy weaving (no runtime-only fallback).
- Does not extend the `ui.interaction` active context window; work started after that window still
  needs an in-flight parent context on the dispatching thread.
- Custom `ThreadPoolExecutor` implementations with exotic work queues may behave unexpectedly.
- Native/NDK thread boundaries are out of scope.
