# Compose Navigation 3 Instrumentation

Status: development

This module emits `ui.navigation` spans for Jetpack Compose Navigation 3 (`androidx.navigation3:navigation3-runtime`).

## Installation

Add the module dependency:

```kotlin
implementation("io.opentelemetry.android.instrumentation:navigation-compose-nav3:<version>")
```

Then attach the observer once for each `NavBackStack`:

```kotlin
@Composable
fun AppNavigation(backStack: NavBackStack) {
  val onBack = rememberVunetOnBack(backStack) { backStack.removeLastOrNull() }
    VunetNav3Observer(backStack = backStack)
  NavDisplay(
    backStack = backStack,
    onBack = onBack,
    entryProvider = { key -> /* ... */ },
  )
}
```

## API

- Public entrypoints:
  - `VunetNav3Observer(backStack: NavBackStack, nameOf: (NavKey) -> String = ...)`
  - `rememberVunetOnBack(backStack: NavBackStack, onBack: () -> Unit): () -> Unit`
- No explicit `OpenTelemetryRum` parameter is required from app code.

## Behavior

- Destination type is emitted as `compose_route`.
- Transition inference from back stack snapshots:
  - stack grows -> `push`
  - stack shrinks -> `pop`
  - same size with different top -> `replace`
  - identical top -> no-op
- Trigger attribution:
  - `push`/`replace` -> `navigation.trigger=unknown`
  - `pop` from wrapped `onBack` callback -> `navigation.trigger=back_press`
  - other `pop` operations (e.g. direct `removeLastOrNull`) -> `navigation.trigger=programmatic`
- `nameOf` can be overridden for typed keys; default uses `simpleName`.

## Schema

This module uses shared constants/emitter/models from `instrumentation/navigation-common`, matching `navigation-view` so backends can query both with the same `ui.navigation` attribute shape.

After each `ui.navigation` span is emitted, `navigation-common` keeps that span as the **active OpenTelemetry context** until the next navigation (or instrumentation uninstall). Downstream work on the new screen—such as OkHttp requests or coroutines with context propagation—parents under the latest `ui.navigation` span instead of an earlier `ui.click` span.

## License

SPDX-License-Identifier: Apache-2.0
