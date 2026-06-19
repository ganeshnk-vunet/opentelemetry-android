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
* Description: Span emitted when a clickable view or composable is tapped.

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
