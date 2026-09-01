/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTION_MODE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
import io.opentelemetry.android.instrumentation.hybrid.click.shared.InteractionType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SELECTION_MODE_MULTIPLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SELECTION_MODE_SINGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_BUTTON
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_CHECKBOX
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_DROPDOWN
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_RADIO
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SWITCH
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TAB
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TEXT
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TOGGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveSelectionMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Locks the **wire key** of the `ui.interaction` toggle-state attribute.
 *
 * [ViewToggleClickTest] and [ClickSpanDurationDecoupledTest] assert through [ATTR_WIDGET_CHECKED],
 * so they keep passing if the constant's string value is reverted or mistyped — the emitted
 * attribute name is the actual contract with dashboards and alerts, and nothing else pins it down.
 *
 * The expected value below is a string literal rather than a reference to the constant itself;
 * referring to the constant would reintroduce exactly the blind spot this test exists to close.
 */
class HybridClickWireKeyContractTest {
    @Test
    fun `toggle checked state uses the canonical wire key`() {
        assertThat(ATTR_WIDGET_CHECKED).isEqualTo("ui.control.value.checked")
    }

    @Test
    fun `superseded wire key is no longer emitted`() {
        assertThat(ATTR_WIDGET_CHECKED).isNotEqualTo("app.widget.checked")
    }

    @Test
    fun `interaction type uses the canonical wire key`() {
        assertThat(ATTR_INTERACTION_TYPE).isEqualTo("interaction.type")
    }

    @Test
    fun `interaction kinds use the canonical vocabulary`() {
        assertThat(InteractionType.TAP.value).isEqualTo("tap")
        assertThat(InteractionType.LONG_PRESS.value).isEqualTo("long_press")
    }

    @Test
    fun `control type uses the canonical wire key`() {
        assertThat(ATTR_CONTROL_TYPE).isEqualTo("ui.control.type")
    }

    @Test
    fun `control selection mode uses the canonical wire key and vocabulary`() {
        assertThat(ATTR_CONTROL_SELECTION_MODE).isEqualTo("ui.control.selection_mode")
        assertThat(SELECTION_MODE_SINGLE).isEqualTo("single")
        assertThat(SELECTION_MODE_MULTIPLE).isEqualTo("multiple")
    }

    @Test
    fun `selection mode is resolved per widget kind`() {
        assertThat(resolveSelectionMode(WIDGET_TYPE_RADIO)).isEqualTo(SELECTION_MODE_SINGLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_TAB)).isEqualTo(SELECTION_MODE_SINGLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_DROPDOWN)).isEqualTo(SELECTION_MODE_SINGLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_SWITCH)).isEqualTo(SELECTION_MODE_MULTIPLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_CHECKBOX)).isEqualTo(SELECTION_MODE_MULTIPLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_TOGGLE)).isEqualTo(SELECTION_MODE_MULTIPLE)
        assertThat(resolveSelectionMode(WIDGET_TYPE_BUTTON)).isNull()
        assertThat(resolveSelectionMode(WIDGET_TYPE_TEXT)).isNull()
    }
}
