/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.activity.startup

import io.opentelemetry.android.common.RumConstants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the **wire keys** of the `app.start` signal.
 *
 * Every other test in this package asserts through the [AppStartupTimer] `EVENT_*` constants and
 * [RumConstants.START_TYPE_KEY], so they keep passing if a constant's string value is reverted or
 * mistyped — the emitted attribute and event names are the actual contract with dashboards and
 * alerts, and nothing else pins them down.
 *
 * Every expected value below is a string literal rather than a reference to the constant it pins;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 *
 * Together with the existing Robolectric tests — which assert that each milestone *is* emitted,
 * addressing it through the same `EVENT_*` constants — this gives the full guarantee: those tests
 * fix which events reach the span, and this one fixes what those events are called on the wire.
 * Changing any name below is a breaking change for consumers and must be a deliberate edit
 * accompanied by a CHANGELOG entry.
 */
class AppStartWireKeyContractTest {
    @Test
    fun `start type attribute uses the canonical wire key`() {
        assertThat(RumConstants.START_TYPE_KEY.key).isEqualTo("app.start.type")
    }

    @Test
    fun `startup phase events use the canonical wire keys`() {
        assertThat(AppStartupTimer.EVENT_PROCESS_CREATION).isEqualTo("app.start.phase.process")
        assertThat(AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_END).isEqualTo("app.start.phase.attach_base_context.end")
        assertThat(AppStartupTimer.EVENT_APPLICATION_CREATED).isEqualTo("app.start.phase.sdk_init")
        assertThat(AppStartupTimer.EVENT_CONTENT_PROVIDERS_END).isEqualTo("app.start.phase.content_providers.end")
        assertThat(AppStartupTimer.EVENT_APPLICATION_POST_CREATED).isEqualTo("app.start.phase.first_activity")
        assertThat(AppStartupTimer.EVENT_TTID).isEqualTo("app.start.phase.initial_display")
    }

    @Test
    fun `phase boundary markers use the canonical wire keys`() {
        assertThat(AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_START)
            .isEqualTo("app.start.phase.attach_base_context.start")
        assertThat(AppStartupTimer.EVENT_CONTENT_PROVIDERS_START)
            .isEqualTo("app.start.phase.content_providers.start")
    }

    /**
     * The two phases that report both a start and an end must keep those two events under one
     * shared prefix, so a duration query can pair them by stripping `.start` / `.end`. A mixed
     * namespace (a `app.*` start against an `app.start.phase.*` end) silently breaks that pairing
     * without breaking any other test.
     */
    @Test
    fun `paired phase events share a prefix so durations can be derived`() {
        val pairs =
            listOf(
                AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_START to AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_END,
                AppStartupTimer.EVENT_CONTENT_PROVIDERS_START to AppStartupTimer.EVENT_CONTENT_PROVIDERS_END,
            )

        for ((start, end) in pairs) {
            assertThat(start).endsWith(".start")
            assertThat(end).endsWith(".end")
            assertThat(start.removeSuffix(".start"))
                .`as`("start/end prefix for %s and %s", start, end)
                .isEqualTo(end.removeSuffix(".end"))
        }
    }

    @Test
    fun `superseded wire keys are no longer emitted by any startup constant`() {
        val supersededNames =
            setOf(
                "app.process.creation",
                "app.attach_base_context.end",
                "app.content_providers.end",
                "applicationCreated",
                "applicationPostCreated",
                "ttid",
                // Interim names from the first pass at canonical naming. Never released, but
                // pinned so a revert to them is caught: each described the wrong thing —
                // `runtime_init` (ART is already up), `extensions` (ContentProvider init),
                // `application` (SDK init, not the end of Application.onCreate) and `ui_ready`
                // (fires before onCreate, layout or first paint).
                "app.start.phase.runtime_init",
                "app.start.phase.extensions",
                "app.start.phase.application",
                "app.start.phase.ui_ready",
            )

        val emittedNames =
            setOf(
                AppStartupTimer.EVENT_PROCESS_CREATION,
                AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_START,
                AppStartupTimer.EVENT_ATTACH_BASE_CONTEXT_END,
                AppStartupTimer.EVENT_CONTENT_PROVIDERS_START,
                AppStartupTimer.EVENT_CONTENT_PROVIDERS_END,
                AppStartupTimer.EVENT_APPLICATION_CREATED,
                AppStartupTimer.EVENT_APPLICATION_POST_CREATED,
                AppStartupTimer.EVENT_TTID,
            )

        assertThat(emittedNames).doesNotContainAnyElementsOf(supersededNames)
        assertThat(RumConstants.START_TYPE_KEY.key).isNotEqualTo("start.type")
    }
}
