# Fragment Instrumentation

Status: development

The fragment instrumentation helps to track the state of your application's Fragment lifecycle.

## Telemetry

This instrumentation produces the following telemetry:

### Fragment state change

* Type: Span
* Name: `fragment.lifecycle`
* Description: As the fragment transitions between states, a span will be created to represent the
  lifecycle of that state. Events are added for subsequent minor state changes.
* SpanEvents: {
  `fragmentPreAttached` | `fragmentAttached` | `fragmentPreCreated` | `fragmentCreated` | `fragmentViewCreated`
  `fragmentStarted` | `fragmentResumed` | `fragmentPaused` | `fragmentStopped` |
  `fragmentViewDestroyed` | `fragmentDestroyed` | `fragmentDetached` }
* Attributes:
    * `fragment.lifecycle.event`: { `Created` | `Restored` | `Resumed` | `Paused` | `Stopped` | `Destroyed` | `ViewDestroyed` | `Detached` }
    * `fragment.name`:  name of fragment
    * `screen.name`:  name of screen
    * `last.screen.name`:  name of screen, when span contains the `fragmentResumed` event.
    * `ui.host.kind`: `fragment` — canonical host discriminator, so one query can select Fragment
      transitions without knowing which span name carries them.
    * `ui.host.name`: canonical successor to `fragment.name`, carrying the identical value.
    * `ui.host.lifecycle.event`: canonical successor to `fragment.lifecycle.event`, in
      **snake_case**: { `created` | `restored` | `resumed` | `paused` | `stopped` | `destroyed` |
      `view_destroyed` | `detached` }. The casing differs deliberately — iOS emits snake_case, so
      this is what lets one cross-platform query group both without folding case per platform.

  The `ui.host.*` attributes are additive: the `fragment.*` attributes above are still emitted, so
  existing queries keep working.

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:fragment:1.3.0-alpha")
```
