plugins {
    id("otel.android-library-conventions")
    id("otel.publish-conventions")
}

description = "OpenTelemetry build-time auto-instrumentation for concurrency context propagation on Android"

android {
    namespace = "io.opentelemetry.android.concurrency.agent"
}

dependencies {
    implementation(project(":instrumentation:concurrency:library"))
    implementation(libs.byteBuddy)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.androidx.core)
}
