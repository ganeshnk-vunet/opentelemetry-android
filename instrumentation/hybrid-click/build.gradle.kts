plugins {
    id("otel.android-library-conventions")
    id("otel.publish-conventions")
}

description = "OpenTelemetry Android hybrid click instrumentation"

android {
    namespace = "io.opentelemetry.android.instrumentation.hybrid.click"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(platform(libs.opentelemetry.platform.alpha)) // Required for sonatype publishing

    implementation(project(":common"))
    implementation(project(":agent-api"))
    implementation(project(":instrumentation:android-instrumentation"))
    implementation(project(":services"))

    compileOnly(libs.compose) {
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "androidx.savedstate")
    }

    // Used to wrap DialogFragment windows so taps inside dialogs are tracked. compileOnly because
    // any app using DialogFragment already provides androidx.fragment at runtime; pure-View apps
    // without it simply skip dialog tracking (guarded at runtime).
    compileOnly(libs.androidx.fragment)

    implementation(libs.opentelemetry.instrumentation.apiSemconv)
    implementation(libs.opentelemetry.semconv.incubating)

    testImplementation(project(":test-common"))
    testImplementation(libs.compose)
    testImplementation(libs.androidx.fragment)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
}
