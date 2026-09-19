# Navigation Common (Internal)

Status: development

This module contains shared navigation telemetry internals used by:

- `instrumentation/navigation-view`
- `instrumentation/navigation-compose-nav2`
- `instrumentation/navigation-compose-nav3`

## Purpose

`navigation-common` centralizes:

- `ui.navigation` span name and attribute keys (`NavigationConstants`)
- span emission logic (`NavigationSpanEmitter`)
- active navigation context for downstream span parenting within the current click interaction (`ActiveInteractionContext` via `NavigationActiveContext`, cleared on the next click or via `NavigationSpanEmitter.clearActiveContext()` on uninstall)

When a click triggers navigation and a later API call runs on another thread, the expected trace shape within that single interaction is:

```
ui.interaction
├── POST (immediate async work)
└── ui.navigation
      └── POST (work after screen transition)
```

Each new click starts a fresh interaction trace. Navigation active context is not session-wide correlation.
- shared navigation models (`NavigationNode`, `NavigationTransitionCandidate`, etc.)

This keeps View and Compose navigation instrumentations aligned on one schema and avoids duplicated logic.

## `navigation.duration_ms`

How long the user waited: from the action that caused the navigation to the moment the destination
was committed. Two sources, in order of specificity:

1. A **back press** the trigger resolver accepted, forwarded by the collector as
   `NavigationTransitionCandidate.intentAtNanos`.
2. The **live click interaction window**, whose root span start is the tap that opened it.

A back press wins when both apply, the same precedence that stops `resolveTrigger` upgrading
`back_press` to `user_tap`.

The attribute is **absent, never zero**, when neither applies. A genuinely programmatic navigation —
a redirect, a timer, a deep link — has no user-perceived wait to report, and a zero would be
indistinguishable from an instant navigation while dragging every percentile down.

> [!IMPORTANT]
> **This metric is biased toward fast navigations, and that has to be read into any dashboard built
> on it.** Both intent sources expire: the interaction window after 500 ms
> (`ClickEventGenerator.DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS`) and a back press after 1 s
> (`NavigationTriggerResolver.BACK_PRESS_SIGNAL_TTL_NANOS`). A navigation slower than its window
> reports *no duration at all* rather than a large one, so the slowest navigations are the ones most
> likely to be missing. Read the distribution as "how long fast navigations took", not "how long
> navigations took", until the attribution window is decoupled from the parenting window.
>
> The interaction window is posted on the main looper, so a navigation delayed by a blocked main
> thread keeps its context; one delayed by background work (a fetch before the screen renders) does
> not.
>
> It also inherits the trigger resolver's misattribution limit: a programmatic navigation landing
> inside an unrelated tap's window is timed from that tap.

`navigation.ttid_ms` — time to initial display — is **not** implemented; it needs a draw callback
and is tracked separately.

## Internal-Only Module

This module is **internal implementation detail** and is **not intended for direct customer use**.

- Customers should not add `navigation-common` directly.
- Customer apps should depend on leaf modules such as:
  - `navigation-view`
  - `navigation-compose-nav2`
  - `navigation-compose-nav3`
- `navigation-common` is pulled transitively by those modules.

## Using navigation modules together

Choose instrumentation modules based on navigation stacks used in the app:

- View-only apps: add `navigation-view`
- Compose Nav2 apps: add `navigation-compose-nav2`
- Compose Nav3 apps: add `navigation-compose-nav3`
- Hybrid apps (View + Compose): add both relevant modules together

Example combinations:

```kotlin
// View + Compose Nav2
implementation("io.opentelemetry.android.instrumentation:navigation-view:<version>")
implementation("io.opentelemetry.android.instrumentation:navigation-compose-nav2:<version>")

// View + Compose Nav3
implementation("io.opentelemetry.android.instrumentation:navigation-view:<version>")
implementation("io.opentelemetry.android.instrumentation:navigation-compose-nav3:<version>")

// Compose Nav2 + Nav3 (during migration)
implementation("io.opentelemetry.android.instrumentation:navigation-compose-nav2:<version>")
implementation("io.opentelemetry.android.instrumentation:navigation-compose-nav3:<version>")
```

You still configure the main OpenTelemetry Android SDK once. Each added navigation instrumentation module auto-registers itself and contributes spans when used by the app.

## Why this split exists

View-based and Compose-based navigation can coexist in one app. By sharing constants, emitter, and models in this module, all navigation instrumentations emit a consistent `ui.navigation` schema.

## License

SPDX-License-Identifier: Apache-2.0
