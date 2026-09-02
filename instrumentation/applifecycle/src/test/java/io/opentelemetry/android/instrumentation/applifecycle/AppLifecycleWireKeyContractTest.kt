/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.applifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys and value space** of the `device.app.lifecycle` span.
 *
 * `AppLifecycleSpanEmitterTest` asserts through `AppLifecycleConstants.*`, so it stays green if a
 * constant's string value is reverted or mistyped — the emitted names/values are the actual
 * contract with dashboards and alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 */
class AppLifecycleWireKeyContractTest {
    @Test
    fun `device app lifecycle uses the canonical span name`() {
        assertThat(AppLifecycleConstants.SPAN_NAME).isEqualTo("device.app.lifecycle")
    }

    @Test
    fun `app state attributes use the canonical wire keys`() {
        assertThat(AppLifecycleConstants.APP_STATE_KEY.key).isEqualTo("app.state")
        assertThat(AppLifecycleConstants.ANDROID_APP_STATE_KEY.key).isEqualTo("android.app.state")
    }

    @Test
    fun `app state vocabulary uses the canonical values`() {
        assertThat(AppLifecycleConstants.STATE_CREATED).isEqualTo("created")
        assertThat(AppLifecycleConstants.STATE_FOREGROUND).isEqualTo("foreground")
        assertThat(AppLifecycleConstants.STATE_BACKGROUND).isEqualTo("background")
    }
}
