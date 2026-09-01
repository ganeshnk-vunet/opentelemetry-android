
# Crash Instrumentation

Status: development

The crash instrumentation detects uncaught exceptions in the user
application and reports these occurrences as telemetry.

## Telemetry

This instrumentation produces the following telemetry:

### Crash

* Type: Event
* Event Name: `device.crash`
* Description: An event that is generated for exceptions not handled by user code.
* Attributes:
    * `error.runtime`: Language runtime that produced the fault. This SDK always emits `jvm`. A Dart or JS exception rethrown into the Android uncaught handler is still `jvm`. Wrappers grouping Flutter/RN faults must emit their own `device.crash` or overwrite this via `addAttributesExtractor` using `dart` / `js`. Deliberate `error.*` extension (semconv currently defines only `error.type`).
    * `exception.message` ([see semconv here](https://github.com/open-telemetry/semantic-conventions/blob/727700406f9e6cc3f4e4680a81c4c28f2eb71569/docs/attributes-registry/exception.md#exception-message))
    * `exception.stacktrace` ([see semconv here](https://github.com/open-telemetry/semantic-conventions/blob/727700406f9e6cc3f4e4680a81c4c28f2eb71569/docs/attributes-registry/exception.md#exception-stacktrace))
    * `exception.type` ([see semconv here](https://github.com/open-telemetry/semantic-conventions/blob/727700406f9e6cc3f4e4680a81c4c28f2eb71569/docs/attributes-registry/exception.md#exception-type))
    * `thread.id` ([see semconv here](https://github.com/open-telemetry/semantic-conventions/blob/727700406f9e6cc3f4e4680a81c4c28f2eb71569/docs/attributes-registry/thread.md#thread-id))
    * `thread.name` ([see semconv here](https://github.com/open-telemetry/semantic-conventions/blob/727700406f9e6cc3f4e4680a81c4c28f2eb71569/docs/attributes-registry/thread.md#thread-name))

Note: This instrumentation supports additional user-configurable `AttributeExtractors` that
may set additional attributes from the given `CrashDetails` (`Thread` and `Throwable`).
Extractors run after the built-in attributes and may replace `error.runtime`; that is the
supported in-process override for a wrapper that still goes through this reporter.

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:crash:1.3.0-alpha")
```
