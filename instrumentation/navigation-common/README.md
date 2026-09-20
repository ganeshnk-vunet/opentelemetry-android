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

1. A **back press** recorded by the collector, forwarded as
   `NavigationTransitionCandidate.intentAtNanos` — used **only on a `POP`**.
2. The **most recent interaction start**, which is the tap that began it
   (`ActiveInteractionContext.lastInteractionStartedAtNanos`).

A back press wins when both apply, the same precedence that stops `resolveTrigger` upgrading
`back_press` to `user_tap`.

> [!NOTE]
> **A back press only times the pop it caused.** A collector holds the press until some transition
> consumes it, and a press does not always produce a pop — it may dismiss a dialog, or the user may
> change their mind and tap forward instead. On a `PUSH`/`REPLACE` the press is therefore ignored
> for timing and the tap below is used, so a forward navigation is never timed from an abandoned
> back press. Pinned by `a_pending_back_press_does_not_time_a_later_forward_navigation`.

### Timing is deliberately not gated on the parenting or trigger windows

Two short windows exist in this code and **neither bounds the duration**:

| Window | Length | What it governs |
|---|---|---|
| `ClickEventGenerator.DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS` | 500 ms | which span a later span is **parented** to |
| `NavigationTriggerResolver.BACK_PRESS_SIGNAL_TTL_NANOS` | 1 s | whether a back press may **name** `navigation.trigger` |

Both are right to be short: a stale signal would attach a span to a long-finished tap, or label a
navigation with a back press that did not cause it. Timing is the opposite case — **the slower a
navigation is, the longer after the action it commits, and the more worth measuring it is.** Reading
the start time through a 500 ms window meant a 3-second navigation reported *no duration at all*
rather than 3 seconds, so the metric showed only the navigations nobody needed to investigate.

The interaction start therefore survives the parenting window's expiry, and collectors forward a
back press whatever the trigger resolver decided. A back navigation that took 2 s is named
`programmatic` (the 1 s trigger TTL expired) **and** carries `navigation.duration_ms = 2000`. That
divergence is intentional: trigger naming and timing answer different questions.

Staleness is bounded by `NavigationSpanEmitter.MAX_ATTRIBUTION_NANOS` (**30 s**) instead — far
longer than any navigation worth recording, short enough that a navigation with no user action
behind it cannot inherit an unrelated earlier tap's timestamp. Beyond it the value is **`0`, not a
clamp**: a clamped value would be indistinguishable from a real navigation of that length.

### Always present; `0` means "not measurable"

`navigation.duration_ms` is set on **every** `ui.navigation` span, so a consumer never has to handle
a missing column. It reports `0` when no trustworthy user-action measurement exists — three cases:

- no user action behind the navigation at all (a redirect, a timer, a deep link, a cold-start
  transition);
- an action older than the attribution limit;
- a destination that committed before its own action.

> [!IMPORTANT]
> **`0` is not an instant navigation.** These rows share the column with real measurements, so any
> average or percentile computed over all `ui.navigation` spans is pulled toward zero. Filter before
> aggregating — **the discriminator is the value itself: `navigation.duration_ms > 0`.** A genuinely
> instant navigation is not a practical concern — a real tap-driven transition does not commit
> within the same millisecond as its tap.

> [!WARNING]
> **Do not filter on `navigation.trigger` to find the measured rows.** It looks like the
> discriminator and is not one. Timing is deliberately not gated on the two short windows above, so
> a slow navigation carries a real duration under a trigger that reads as unmeasured:
>
> | Navigation | `navigation.trigger` | `navigation.duration_ms` |
> |---|---|---|
> | tap → commit in 80 ms | `user_tap` | `80` |
> | tap → commit in 2 s (500 ms parenting window closed, so never upgraded) | `unknown` | `2035` |
> | back press → commit in 2 s (1 s trigger TTL expired) | `programmatic` | `2000` |
> | launch, redirect, timer — no user action at all | `programmatic` / `unknown` | `0` |
>
> Keeping only `user_tap`/`back_press` keeps row 1 and drops rows 2 and 3 — exactly the slow
> navigations this attribute exists to surface. `navigation.trigger` answers "what caused this?";
> `navigation.duration_ms > 0` answers "is this measured?" Pinned by
> `a_slow_navigation_carries_a_real_duration_under_an_unmeasured_looking_trigger`.

### A tap times one navigation

The interaction start is **claimed by the first navigation that uses it**. A tap explains the screen
it opens and nothing the app does afterwards on its own.

Without the claim, the start time stayed readable for the full 30 s limit, so this happened:

1. You tap Profile. Profile arrives in 100 ms → `duration_ms = 100`. Correct.
2. You sit on Profile. No taps.
3. Twenty seconds later the session expires and the app moves you to Login.
4. Login reported `duration_ms = 20000` — a twenty-second wait nobody experienced, and one that
   survives a `duration_ms > 0` filter because it is not zero.

Step 4 now reports `0`. Same for a deep link, a timer, or any "continue to next step" the user did
not ask for.

The claim is **not exclusive immediately**. Two navigation collectors can be active in one process —
a Compose host that also runs the View collector emits a `ui.navigation` span from each for the same
navigation — and a hard claim would give the first a duration and the second `0`. Both are served
for `ActiveInteractionContext.CONCURRENT_COLLECTOR_GRACE_NANOS` (**250 ms**) after the first claim,
sized for a second collector reacting to one navigation, not for a second navigation.

> [!NOTE]
> A navigation chain that steps again within that 250 ms is still timed from the tap, so those steps
> accumulate rather than partition. Left alone deliberately: at that spacing the user really did
> wait from the tap, so the number is not wrong. What the window has to exclude is idle time, which
> is orders of magnitude larger.

### What it does and does not cover

It measures up to the point the navigation framework reports the destination as current. Time the
destination then spends composing or loading before anything is drawn is **`navigation.ttid_ms`,
which is not implemented** and needs a draw callback.

Remaining limits, both inherited from the trigger resolver:

- A programmatic navigation within 30 s of an unrelated tap is timed from that tap. Every tap
  refreshes the interaction start, so in practice this needs a navigation with no user activity at
  all before it.
- A chain of navigations from one tap each reports time since that tap, so the steps accumulate
  rather than partition.

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
