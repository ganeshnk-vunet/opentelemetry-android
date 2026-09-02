/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Locks the **wire keys and value space** of the canonical `ui.host.*` lifecycle attributes.
 *
 * The activity and fragment tests assert through `RumConstants.*`, so they keep passing if a
 * constant's string value is reverted or mistyped — the emitted names are the actual contract with
 * dashboards and alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Changing any name or value below is a breaking change for consumers and must be a deliberate edit
 * accompanied by a CHANGELOG entry.
 */
class UiHostWireKeyContractTest {
    @Test
    fun `ui host attributes use the canonical wire keys`() {
        assertThat(RumConstants.UI_HOST_KIND_KEY.key).isEqualTo("ui.host.kind")
        assertThat(RumConstants.UI_HOST_NAME_KEY.key).isEqualTo("ui.host.name")
        assertThat(RumConstants.UI_HOST_LIFECYCLE_EVENT_KEY.key).isEqualTo("ui.host.lifecycle.event")
    }

    @Test
    fun `ui host kind vocabulary uses the canonical values`() {
        assertThat(RumConstants.UI_HOST_KIND_ACTIVITY).isEqualTo("activity")
        assertThat(RumConstants.UI_HOST_KIND_FRAGMENT).isEqualTo("fragment")
    }

    /**
     * The complete event vocabulary both hosts can emit. `ViewDestroyed` is the only multi-word
     * value, and the only one a plain `lowercase()` would get wrong — it is the reason this
     * conversion exists rather than being inlined at the call sites.
     */
    @Test
    fun `every lifecycle event maps to its canonical snake_case value`() {
        // Activity.
        assertThat(RumConstants.uiHostLifecycleEventOf("Created")).isEqualTo("created")
        assertThat(RumConstants.uiHostLifecycleEventOf("Restarted")).isEqualTo("restarted")
        assertThat(RumConstants.uiHostLifecycleEventOf("Resumed")).isEqualTo("resumed")
        assertThat(RumConstants.uiHostLifecycleEventOf("Paused")).isEqualTo("paused")
        assertThat(RumConstants.uiHostLifecycleEventOf("Stopped")).isEqualTo("stopped")
        assertThat(RumConstants.uiHostLifecycleEventOf("Destroyed")).isEqualTo("destroyed")

        // Fragment adds these three.
        assertThat(RumConstants.uiHostLifecycleEventOf("Restored")).isEqualTo("restored")
        assertThat(RumConstants.uiHostLifecycleEventOf("Detached")).isEqualTo("detached")
        assertThat(RumConstants.uiHostLifecycleEventOf("ViewDestroyed")).isEqualTo("view_destroyed")
    }

    @Test
    fun `already lowercase input passes through unchanged`() {
        assertThat(RumConstants.uiHostLifecycleEventOf("starting")).isEqualTo("starting")
    }

    /**
     * `ui.host.*` is additive: the superseded per-host keys are still emitted alongside it, so
     * existing queries keep working. This pins that promise — the inverse of the usual
     * "superseded key is gone" assertion.
     */
    @Test
    fun `superseded per-host wire keys are still emitted`() {
        assertThat(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY.key).isEqualTo("activity.lifecycle.event")
        assertThat(RumConstants.FRAGMENT_LIFECYCLE_EVENT_KEY.key).isEqualTo("fragment.lifecycle.event")
        assertThat(RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME).isEqualTo("activity.lifecycle")
        assertThat(RumConstants.FRAGMENT_LIFECYCLE_SPAN_NAME).isEqualTo("fragment.lifecycle")
    }
}
