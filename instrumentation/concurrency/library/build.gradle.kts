plugins {
    id("otel.android-library-conventions")
    id("otel.publish-conventions")
}

description = "OpenTelemetry concurrency context propagation library for Android"

android {
    namespace = "io.opentelemetry.android.concurrency.library"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(platform(libs.opentelemetry.platform.alpha))
    api(libs.opentelemetry.extension.kotlin)
    implementation(libs.opentelemetry.context)
    implementation(libs.opentelemetry.api)
    implementation(libs.byteBuddy)
    implementation(project(":instrumentation:android-instrumentation"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.androidx.core)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
}
