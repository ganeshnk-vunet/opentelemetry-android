
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
* Span events (cold start): `app.start.phase.process`, `app.attach_base_context.start`,
  `app.start.phase.runtime_init` (require [startup-agent](../startup/README.md) and a declared
  `attachBaseContext` override on your `Application` subclass), `app.content_providers.start`,
  `app.start.phase.extensions`, `app.start.phase.application`, `app.start.phase.ui_ready`,
  `app.start.phase.initial_display`

  The `app.start.phase.*` names are the canonical startup-phase milestones. The two
  `*.start` boundary markers keep their original names because the canonical phase model
  defines a milestone only at the *end* of each phase.

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

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:activity:1.3.0-alpha")
```
