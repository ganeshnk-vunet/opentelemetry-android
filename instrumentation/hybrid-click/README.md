# Hybrid Click Instrumentation

Status: development

This instrumentation captures click interactions for both Android Views and Jetpack Compose
using a single `Window.Callback` wrapper to avoid callback wrapping conflicts.

This instrumentation is not currently enabled by default.

## Telemetry

Data produced by this instrumentation uses instrumentation scope name
`io.opentelemetry.android.instrumentation.hybrid.click`.

### Clicks

* Type: Span
* Name: `ui.click`
* Description: Span emitted when a clickable view or composable is tapped. Each tap starts a **new interaction trace** so downstream async work (for example HTTP requests on background threads, when concurrency instrumentation is enabled) correlates with that click only — not with `app.start` or prior clicks.

The span is kept active for a configurable window (default 500 ms) so work triggered by the tap can inherit the click context. A new tap clears any stale navigation context from a prior interaction before starting its trace.

## Installation

```kotlin
implementation("io.opentelemetry.android.instrumentation:hybrid-click:1.2.0-alpha")
```

## Configuration

When using `android-agent`, you can configure the active click context window:

```kotlin
OpenTelemetryRumInitializer.initialize(
    context = applicationContext,
) {
    instrumentations {
        hybridClick {
            activeContextWindowMillis(500)
        }
    }
}
```
