plugins {
    id("otel.android-app-conventions")
    id("net.bytebuddy.byte-buddy-gradle-plugin")
}

android.namespace = "io.opentelemetry.android.concurrency.testing"

dependencies {
    byteBuddy(project(":instrumentation:concurrency:agent"))
    byteBuddy(project(":instrumentation:okhttp3:agent"))
    implementation(project(":instrumentation:concurrency:library"))
    implementation(project(":instrumentation:okhttp3:library"))
    implementation(project(":test-common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.opentelemetry.exporter.otlp)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
}
