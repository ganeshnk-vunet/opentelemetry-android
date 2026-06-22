# OpenTelemetry Android Versioning

This document addresses versioning and release considerations for this fork.

## VuNet fork — Maven Central versioning

This fork (`com.vunetsystems.opentelemetry.android`) uses a single published version for
**every** module so consumers can align dependencies with one BOM coordinate.

### Published coordinates

| Role | Maven coordinate (snapshot) |
|------|-----------------------------|
| BOM | `com.vunetsystems.opentelemetry.android:opentelemetry-android-bom:0.0.1-SNAPSHOT` |
| Agent entry | `com.vunetsystems.opentelemetry.android:android-agent:0.0.1-SNAPSHOT` |
| Instrumentation | `com.vunetsystems.opentelemetry.android.instrumentation:<artifact>:0.0.1-SNAPSHOT` |

Releases use the same coordinates without `-SNAPSHOT` (e.g. `0.0.1`).

Repository: [Maven Central](https://central.sonatype.com/artifact/com.vunetsystems.opentelemetry.android/android-agent)

The [vuTelemetry-android](https://github.com/vunetsystems/vutelemetry-android) SDK and Gradle plugin
should pin the same BOM version when building against this fork.

### How the version string is built

Configured in root `gradle.properties`:

| Property | Value | Effect |
|----------|-------|--------|
| `version` | `0.0.1` | Base semver |
| `otel.publish.alpha` | `false` | Does **not** append `-alpha` (all modules share one version) |
| `final` (Gradle property) | unset | Appends `-SNAPSHOT` |

Default publish result: **`0.0.1-SNAPSHOT`**.

Release build: publish with `-Pfinal=true` → **`0.0.1`** for all modules.

### CI publish triggers

| Branch event | Published version |
|--------------|-------------------|
| PR merged to `develop` | `0.0.1-SNAPSHOT` |
| push to `release/*` | `0.0.1` (must be greater than latest on Central) |

### Bumping versions

1. Update `version` in `gradle.properties` (e.g. `0.0.2`).
2. Merge to `develop` for snapshots, or `release/*` for releases.
3. Update the matching BOM version in vuTelemetry-android.

### Resource version attributes (VuNet integration)

This fork no longer exports the standard OpenTelemetry `telemetry.sdk.language`,
`telemetry.sdk.name`, or `telemetry.sdk.version` resource attributes. `AndroidResource`
builds resources without merging `Resource.getDefault()`, which is where those attributes
originate. Instead, the
[vuTelemetry-android](https://github.com/vunetsystems/vutelemetry-android) Gradle plugin and SDK
contribute the following **resource** attributes via the existing `resource { }` DSL in
`OpenTelemetryRumInitializer.initialize`:

| Resource attribute | Source |
|--------------------|--------|
| `vunet.opentelemetry.version` | Resolved `opentelemetry-android-bom` version on the consumer classpath |
| `vunet.gradle.plugin.version` | `vunet.telemetry.android` Gradle plugin version |
| `vunet.sdk.version` | vuTelemetry SDK artifact version |

These attributes flow to logs and metrics on every export, and to the first cold `app.start`
trace span via `SelectiveResourceSpanExporter`.

#### vuTelemetry-android implementation contract

The Gradle plugin should generate a Kotlin source file (e.g. `VunetBuildInfo.kt`) into the SDK
module's generated sources:

```kotlin
internal object VunetBuildInfo {
    const val OPENTELEMETRY_VERSION = "<resolved BOM version>"
    const val GRADLE_PLUGIN_VERSION = "<plugin version>"
    const val SDK_VERSION = "<sdk version>"
}
```

`VunetAutoInitProvider` should contribute the attributes at init time:

```kotlin
OpenTelemetryRumInitializer.initialize(ctx) {
    resource {
        put("vunet.opentelemetry.version", VunetBuildInfo.OPENTELEMETRY_VERSION)
        put("vunet.gradle.plugin.version", VunetBuildInfo.GRADLE_PLUGIN_VERSION)
        put("vunet.sdk.version", VunetBuildInfo.SDK_VERSION)
    }
}
```

## Versioning scheme

This codebase uses [Semantic Versioning](https://semver.org/) (semver) for its version numbers.
All modules in this codebase are released at the same time and, as such, will
be versioned together. All modules in this repo are released with the same version number.

Until 1.0.0 stability has been achieved, regular releases will only typically increment
the minor version (second number in the semver triplet). Patch releases are considered
exceptional, and will only be created if a critical issue needs to be addressed shortly after
a regular release.

## Snapshot builds

Snapshot builds are published to Maven Central when PRs merge to `develop`.
Users may choose to build and test and file issues against SNAPSHOT
builds, but their use in production is strongly discouraged.

## Android ecosystem compatibility

The android-agent currently supports the following minimum versions:

- Kotlin 2.0
- API 23+ ([desugaring of the core library](https://developer.android.com/studio/write/java8-support#library-desugaring) required for API <26)
- Android Gradle Plugin (AGP) 7.4 and Gradle 7.5
- JDK 11 (build-time)
- Java language level 8 as per [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/blob/main/VERSIONING.md#language-version-compatibility)

These versions can be bumped in a major version release when:

1. [Google Play Services](https://developers.google.com/android/guides/setup) drops support for any of the above versions
2. A new version of Kotlin is released that drops support for the minimum Kotlin version that
   opentelemetry-android targets. In practice, Kotlin usually supports the last 4 minor versions of
   Kotlin.
3. At the discretion of maintainers after discussing in the SIG

These are **minimum** supported versions. We would strongly recommend using newer versions where
possible as that's where our testing will be focused.

## Release schedule

This Android library is built on top of other OpenTelemetry components:
* [opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java)
* [opentelemetry-java-instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
* [opentelemetry-java-contrib](https://github.com/open-telemetry/opentelemetry-java-contrib)

As such, this project will follow a release schedule that is related to the upstream release
schedule. Presently, this means a monthly release, typically within a week of the last
release of the above components.

## Internal packages

Java and/or Kotlin code that lives in a package with `internal` anywhere in the name
should be considered internal to the project. Code in `internal` packages is not intended
for direct use, even if classes or methods may be public.

Internal code may change at any time and carries no API stability constraints.
