# View Navigation Instrumentation

Status: development

This module emits **`ui.navigation` spans** when the user-visible screen changes due to traditional
Android **Activity** and **Fragment** lifecycles (XML / View-based host). It reflects *which screen
became visible* and a best-effort **transition classification** (`push`, `replace`, `pop`). It does
not instrument Jetpack **Navigation Compose** nor **Jetpack Navigation** graphs directly; those
eventually surface here only if Activity/Fragment lifecycles change.

## What gets tracked

| Source | When a span fires |
|--------|-------------------|
| **Activities** | `Activity.onResume` when the resumed Activity is a **new logical destination** (not the same as the last tracked node). |
| **Fragments** | `Fragment.onResume` for the **top-level** visible fragment of a `FragmentManager` (no parent fragment; not a `DialogFragment`). |

Excluded on purpose:

* **`DialogFragment`** — dialogs are treated as overlays, not full navigation.
* **Child fragments** — only fragments whose `parentFragment == null` are reported as navigation
  destinations for that manager (typical pattern: one primary screen fragment per Activity).

Instrumentation id (for loaders / debugging): **`navigation.view`**.

## Telemetry

Instrumentation is registered via **`@AutoService(AndroidInstrumentation::class)`**. Spans are built
with tracer scope **`io.opentelemetry.lifecycle`** (**`instrumentation/common-api`** —
**`Constants.INSTRUMENTATION_SCOPE`**), consistent with other lifecycle-oriented instrumentation in
this repository.

### Navigation transition span

| | |
|---|---|
| **Type** | Span |
| **Name** | `ui.navigation` |
| **Duration** | Effectively instantaneous (started and ended in the emitter on the reporting path). Timestamp semantics are dominated by **`navigation.timestamp_ns`** and the OTLP span start time. |

**Attributes** (conceptual keys; Kotlin code uses typed `AttributeKey` values in `navigation-common` `NavigationConstants`):

| Attribute | Semantics |
|-----------|-----------|
| **`navigation.destination.type`** | **`activity`** or **`fragment`** (lowercase enum name). |
| **`navigation.destination.name`** | Human-readable destination label from **`ScreenNameExtractor`** (defaults to **`DefaultScreenNameExtractor`**). |
| **`navigation.transition.type`** | **`push`** — new destination on stack; **`pop`** — back / finish surfaced as return to previous Activity; **`replace`** — inferred when a Fragment replaces another without shrinking the fragment back stack. |
| **`navigation.entry.type`** | Only meaningful for Activity-driven transitions when that Activity **first** resumes (`internal`, `deep_link`, `external` — see [Entry-type heuristics](#entry-type-heuristics)). Fragment transitions always emit **`internal`**. Subsequent resumes of the **same Activity instance** use **`internal`**. |
| **`navigation.timestamp_ns`** | **`Clock.now()`** from **`OpenTelemetryRum`** — **nanoseconds since Unix epoch**, matching OpenTelemetry SDK `Clock` units. |

When a prior screen exists (**not** cold start):

| Attribute | Semantics |
|-----------|-----------|
| **`navigation.source.type`** | **`activity`** or **`fragment`**. |
| **`navigation.source.name`** | Resolved name of the previously visible navigation node. |
| **`last.screen.name`** | Same semantic as source name (shared RUM key from **`common`**). |

Also set:

| Attribute | Semantics |
|-----------|-----------|
| **`screen.name`** | Destination screen name (matches destination; applied after span start so it wins over default appenders where relevant). |

**Not emitted:** causes such as user tap vs programmatic navigation are intentionally **not**
encoded as attributes here. If **`hybrid-click`** / **`view-click`** runs in the same app, a tap
that synchronously triggers navigation typically leaves **`ui.interaction`** current when the lifecycle
runs, so backends can correlate **`parentSpanId`** → **`ui.interaction`** vs no parent trace link.

### Entry-type heuristics

`navigation.entry.type` is derived once per **Activity instance** from its launch **`Intent`** (first
resume only):

| Value | Condition |
|-------|-----------|
| **`deep_link`** | `intent.data != null` **and** `intent.action == Intent.ACTION_VIEW`. |
| **`external`** | `intent.action != null` and not **`Intent.ACTION_MAIN`**, and not classified as **`deep_link`**. |
| **`internal`** | Everything else (`ACTION_MAIN`, null action, launcher-style launch, subsequent resumes). |

## Installation

Unlike some default agent modules, **`navigation-view` is not** listed in **`android-agent`**
`dependencies` today. Add it explicitly if you want this telemetry:

```kotlin
implementation("io.opentelemetry.android.instrumentation:navigation-view:<version>")
```

If you use **`OpenTelemetryRumBuilder`** **without** the fat agent, register the module (or rely on
**`ServiceLoader`** discovery of **`AndroidInstrumentation`** per your setup). When using the
builder’s instrumentation loader, ensure this artifact is on the classpath so
**`ViewNavigationInstrumentation`** is discovered.

**`uninstall`** unregisters Activity callbacks and calls **`ViewNavigationCollector.cleanup()`** so
per-`FragmentManager` lifecycle listeners are removed.

## Implementation notes (for maintainers & power users)

* **Activity `pop`:** when the paused Activity’s **`isFinishing`** is true, the next **`onResume`**
  on another Activity is classified as **`pop`**. This does **not** distinguish user back press from
  **`finish()`** in code.
* **Fragment `pop` vs `replace` vs `push`:** driven by **FragmentManager back stack count** and
  whether the current visible node was already a **fragment**. Forward **`replace()`** that removes
  the previous fragment does **not** rely on `onFragmentDestroyed` for classification (avoids
  mis-labeling replace as pop).
* **Registration scope:** one **`FragmentManager.FragmentLifecycleCallbacks`** per
  **`FragmentActivity.supportFragmentManager`**, keyed weakly so managers are not leaked if unpaired
  destroy paths occur.
* **Tests:** **`instrumentation/navigation-view/src/test/...`** (JUnit 5 + MockK).

## Related instrumentation

| Module | Relation |
|--------|----------|
| **[activity](../activity/)** | Activity lifecycle spans/events (not the same as screen-to-screen **`ui.navigation`**). |
| **[fragment](../fragment/)** | Fine-grained fragment lifecycle instrumentation. |
| **[hybrid-click](../hybrid-click/)** | **`ui.interaction`** spans; correlates via trace parent when navigation runs under click context. |
| **[compose/click](../compose/click/)** | Compose tap instrumentation (**`ui.click`**). Compose navigation surfaces here only when underlying Activity / Fragment lifecycle changes justify it. |

## Module layout (`src/main`)

| Package / area | Role |
|----------------|------|
| **`ViewNavigationInstrumentation`** | `AndroidInstrumentation` entry; **`name = navigation.view`**. |
| **`ViewNavigationCollector`** | **`Application.ActivityLifecycleCallbacks`** + fragment callbacks; builds **`NavigationTransitionCandidate`**. |
| **`NavigationSpanEmitter`** | Shared emitter from `navigation-common`; creates **`ui.navigation`** spans and attributes. |
| **`models/ResolveEntryType`** | Activity intent heuristic (`internal`/`deep_link`/`external`) kept in `navigation-view`. |

## License

SPDX-License-Identifier: Apache-2.0
