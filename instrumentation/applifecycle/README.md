
# App Lifecycle Instrumentation

Status: development

This instrumentation exports the canonical `device.app.lifecycle` signal: one span per
app-level state transition (process foreground / background), plus one `created` span emitted
at install time. It builds directly on the SDK's existing process-level foreground/background
detection (`ProcessLifecycleOwner`, already used internally for session timeout, ANR polling,
and network-change gating) — this module just also exports it as telemetry.

**This is distinct from two other signals and must not be confused with either:**

* `app.start` — startup/resume **timing**, not app-level state.
* `activity.lifecycle` / `fragment.lifecycle` — per-UI-host spans, fired once per Activity or
  Fragment. A single foreground/background transition can involve several of those; this
  instrumentation instead reports one signal for the whole process, regardless of how many
  Activities or Fragments exist.

## Telemetry

This instrumentation produces the following telemetry:

### App lifecycle

* Type: Standalone Span
* Span Name: `device.app.lifecycle`
* Description: Emitted once per app-level state transition — `created` (once, at install),
  `foreground`, and `background`.
* Attributes:
  * `app.state`: Cross-platform canonical app state.
  * `android.app.state`: OTel Android wire format for the same state. Carries the identical
    value as `app.state`.
  * Values: `created` · `foreground` · `background`.

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:applifecycle:<version>")
```
