
# Activity Instrumentation

Status: development

The activity instrumentation helps to track the state of your application's
Activity and the lifecycle. This instrumentation also currently measures
and reports some application startup telemetry.

## Telemetry

This instrumentation produces the following telemetry:

### Application Start

* Type: Span
* Name: `app.start`
* Description: This span is created and started when the Activity instrumentation is
  installed. It is ended when the first frame is drawn (TTID) or when the initial activity
  reaches PostPaused, PostStopped, or PostDestroyed.
* Attributes:
  * `app.start.type`: { `cold` | `hot` | `warm` }
* Resource (trace export): the **first cold** `app.start` span includes the full OTLP resource block
  (`device.*`, `os.*`, `app.installation.id`, `service.*`, etc.). All other trace spans carry a
  minimal resource (`service.name` only). Logs and metrics always use the full resource.
* Span events (cold start): `app.start.phase.process`,
  `app.start.phase.attach_base_context.start` / `.end` (require
  [startup-agent](../startup/README.md) and a declared `attachBaseContext` override on your
  `Application` subclass), `app.start.phase.content_providers.start` / `.end`,
  `app.start.phase.sdk_init`, `app.start.phase.first_activity`,
  `app.start.phase.initial_display`

  Each name describes the probe it is taken from, not a generic phase:
  * `attach_base_context.end` — the first `Application` callback completing. The ART runtime is
    already running well before this, so it is not a runtime-init marker.
  * `content_providers.end` — end of the ContentProvider init phase.
  * `sdk_init` — the OTel SDK finished initialising, partway through `Application.onCreate()`.
    Not the end of that callback.
  * `first_activity` — the first `onActivityPreCreated`. It fires *before* `Activity.onCreate`,
    layout, and first paint; first paint is `app.start.phase.initial_display`.

  The two phases that report both a boundary and an end keep them under one shared prefix, so a
  duration query can pair them by stripping `.start` / `.end`.

### Activity state change

* Type: Span
* Name: `activity.lifecycle`
* Description: As the activity transitions between states, a span will be created to represent the
  lifecycle of that state. Events are added for subsequent minor state changes.
* SpanEvents: {
  `activityPreCreated` | `activityCreated` | `activityPostCreated` |
  `activityPreStarted` | `activityStarted` | `activityPostStarted` |
  `activityPreResumed` | `activityResumed` | `activityPostResumed` |
  `activityPrePaused` | `activityPaused` | `activityPostPaused` |
  `activityPreStopped` | `activityStopped` | `activityPostStopped` |
  `activityPreDestroyed` | `activityDestroyed` | `activityPostDestroyed` }
* Attributes:
  * `activity.lifecycle.event`: { `Created` | `Resumed` | `Paused` | `Stopped` | `Destroyed` | `Restarted` }
  * `activity.name`:  name of activity
  * `screen.name`:  name of screen
  * `last.screen.name`:  name of screen, only when span contains the `activityPostResumed` event.
  * `ui.host.kind`: `activity` — canonical host discriminator, so one query can select Activity
    transitions without knowing which span name carries them.
  * `ui.host.name`: canonical successor to `activity.name`, carrying the identical value.
  * `ui.host.lifecycle.event`: canonical successor to `activity.lifecycle.event`, in **snake_case**:
    { `created` | `resumed` | `paused` | `stopped` | `destroyed` | `restarted` }. The casing differs
    deliberately — iOS emits snake_case, so this is what lets one cross-platform query group both
    without folding case per platform.

  The `ui.host.*` attributes are additive: the `activity.*` attributes above are still emitted, so
  existing queries keep working.

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:activity:1.3.0-alpha")
```
