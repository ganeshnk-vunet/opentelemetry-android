
# Slow Rendering Instrumentation

Status: development

The OpenTelemetry slow rendering instrumentation for Android will detect when
the application user interface is slow or frozen.
[See the Android documentation for a discussion of UI "jank"](https://developer.android.com/studio/profile/jank-detection).

The instrumentation operates by periodically polling for frame metrics, by default
every 1 second.

## Telemetry

This instrumentation produces the following telemetry, with an instrumentation
scope of `app.jank`.

### Slow Renders (Span)

Generated when rendering takes more than 16ms within a polling period.

* Type: Span (zero duration)
* Name: `app.jank`
* Description: This span is emitted when frame metrics contain at least
  one render duration longer than 16ms (the slow rendering threshold).
* Attributes:
  * `app.jank.frame_count` - the number of frames that exceeded the threshold, including frozen frames
  * `app.jank.period` - the polling period duration in seconds during which the frames were detected
  * `app.jank.threshold` - the threshold in seconds above which a frame is considered slow (e.g. `0.016`)
  * `app.jank.type` - `slow`

### Frozen Renders (Span)

Generated when rendering takes more than 700ms within a polling period.

* Type: Span (zero duration)
* Name: `app.jank`
* Description: This span is emitted when frame metrics contain at least
  one render duration longer than 700ms (the frozen rendering threshold).
* Attributes:
  * `app.jank.frame_count` - the number of frames that exceeded the threshold
  * `app.jank.period` - the polling period duration in seconds during which the frames were detected
  * `app.jank.threshold` - the threshold in seconds above which a frame is considered frozen (e.g. `0.7`)
  * `app.jank.type` - `frozen`

> **Bucketing is cumulative.** A frozen frame exceeds both thresholds, so it is reported by both
> the slow and the frozen span. Group or filter by `app.jank.type` rather than counting `app.jank`
> spans, which double-counts frozen frames. Frozen-only is `app.jank.type="frozen"`. Slow but
> not frozen is `sum(app.jank.frame_count)` where `type="slow"` minus `sum(app.jank.frame_count)`
> where `type="frozen"` — not a span-count subtraction.

### Deprecated: Zero-Duration Spans

> **Deprecated.** Zero-duration spans are no longer emitted by default. They can be re-enabled
> via `enableDeprecatedZeroDurationSpan()` for backwards compatibility, but this is discouraged.
> Use the `app.jank` spans above instead.
>
> **Bucketing is not equivalent.** `slowRenders`/`frozenRenders` are exclusive — a frame counts as
> `slowRenders` only if it is *not* also over the frozen threshold — while `app.jank.type="slow"`
> is cumulative and includes frozen frames (see above). A `count` that was `slowRenders` under the
> old spans will be **higher** as `sum(app.jank.frame_count)` where `app.jank.type="slow"`; they are
> not directly comparable without subtracting `sum(app.jank.frame_count)` where
> `app.jank.type="frozen"` first. Subtracting span counts is not equivalent.

When enabled via `enableDeprecatedZeroDurationSpan()`, the instrumentation additionally produces
spans named `slowRenders`/`frozenRenders` with an instrumentation scope of
`io.opentelemetry.slow-rendering`.

#### Slow Renders (Span)

* Type: Span (zero duration)
* Name: `slowRenders`
* Attributes:
  * `count` - the number of slow renders
  * `activity.name` - the name of the activity for which the slow render was detected

#### Frozen Renders (Span)

* Type: Span (zero duration)
* Name: `frozenRenders`
* Attributes:
  * `count` - the number of frozen renders
  * `activity.name` - the name of the activity for which the frozen render was detected

## Installation

This instrumentation comes with the [android agent](../../android-agent) out of the box, so
if you depend on it, you don't need to do anything else to install this instrumentation.
However, if you don't use the agent but instead depend on [core](../../core) directly, you can
manually install this instrumentation by following the steps below.

### Adding dependencies

```kotlin
implementation("io.opentelemetry.android.instrumentation:slowrendering:1.3.0-alpha")
```
