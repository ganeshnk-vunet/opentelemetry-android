plugins {
    id("otel.android-library-conventions")
    id("otel.publish-conventions")
}

android {
    namespace = "io.opentelemetry.android"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "OTEL_ANDROID_VERSION", "\"$version\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    androidComponents {
        onVariants {
            if (it.buildType == "release") { // The one we choose to release
                project.tasks.register("createReleaseBuild", Copy::class) {
                    from(it.artifacts.get(com.android.build.api.artifact.SingleArtifact.AAR))
                    into(project.layout.buildDirectory.dir("outputs/aar"))
                    rename(".+", "opentelemetry-android.aar")
                }
            }
        }
    }

    project.afterEvaluate {
        tasks.named("assembleRelease") {
            finalizedBy("createReleaseBuild")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("test") {
            kotlin.srcDirs("src/integrationTest/kotlin")
        }
    }
}

dependencies {
    api(platform(libs.opentelemetry.platform.alpha)) // Required for sonatype publishing

    implementation(project(":agent-api"))
    implementation(project(":instrumentation:android-instrumentation"))
    implementation(project(":common"))
    implementation(project(":services"))
    implementation(project(":session"))

    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.logging)
    implementation(libs.opentelemetry.instrumentation.api)
    implementation(libs.opentelemetry.semconv.incubating)
    implementation(libs.opentelemetry.diskBuffering)
    implementation(libs.opentelemetry.processors)
    constraints {
        // Raise contrib's transitive wire-runtime and jackson-core without depending on them. Remove once
        // contrib resolves wire-runtime >= 6.4.5 and jackson-core >= 2.21.7 on its own. Gradle consumers get
        // them from module metadata; the POM lists them only under dependencyManagement, which Maven
        // doesn't apply to consumers.
        implementation(libs.wire.runtime) { because("CVE-2026-45799, CVE-2026-63126") }
        implementation(libs.jackson.core) { because("CVE-2026-68494, CVE-2026-89407, CVE-2026-89425") }
    }
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit.ktx)
}

// Fails `check` if the shipped classpath resolves a version below a CVE floor again, e.g. when the
// constraints above are dropped while contrib still pulls the vulnerable versions.
val verifyDependencyFloors by tasks.registering {
    val floors =
        mapOf(
            "com.squareup.wire:wire-runtime" to libs.versions.wire.get(),
            "com.squareup.wire:wire-runtime-jvm" to libs.versions.wire.get(),
            "com.fasterxml.jackson.core:jackson-core" to libs.versions.jackson.get(),
        )
    val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    inputs.property("floors", floors)
    doLast {
        fun below(
            version: String,
            floor: String,
        ): Boolean {
            val v = version.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val f = floor.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(v.size, f.size)) {
                val a = v.getOrElse(i) { 0 }
                val b = f.getOrElse(i) { 0 }
                if (a != b) return a < b
            }
            return false
        }
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root.get()))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            component.moduleVersion?.let { id ->
                floors["${id.group}:${id.name}"]?.let { floor ->
                    if (below(id.version, floor)) problems += "${id.group}:${id.name}:${id.version} (needs >= $floor)"
                }
            }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        check(problems.isEmpty()) { "releaseRuntimeClasspath resolves known-vulnerable versions:\n" + problems.joinToString("\n") }
    }
}

tasks.named("check") { dependsOn(verifyDependencyFloors) }

extra["pomName"] = "OpenTelemetry Android Instrumentation"
description = "A library for instrumenting Android applications with OpenTelemetry"
