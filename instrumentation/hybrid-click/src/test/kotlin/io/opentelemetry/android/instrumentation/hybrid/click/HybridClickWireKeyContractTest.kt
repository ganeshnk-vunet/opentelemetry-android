/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTION_MODE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_VALUE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_GESTURE_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
import io.opentelemetry.android.instrumentation.hybrid.click.shared.GestureType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.INTERACTION_TYPE_SLIDER
import io.opentelemetry.android.instrumentation.hybrid.click.shared.INTERACTION_TYPE_TOGGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SELECTION_MODE_MULTIPLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SELECTION_MODE_SINGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_BUTTON
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_CHECKBOX
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_DROPDOWN
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_RADIO
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SLIDER
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SWITCH
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TAB
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TEXT
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_TOGGLE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveInteractionType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveSelectionMode
import io.opentelemetry.android.instrumentation.hybrid.click.view.ViewTapTargetDetector
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
    fun `gesture kinds use the canonical vocabulary`() {
        assertThat(GestureType.TAP.value).isEqualTo("tap")
        assertThat(GestureType.LONG_PRESS.value).isEqualTo("long_press")
    }

    @Test
    fun `gesture type uses the canonical wire key`() {
        assertThat(ATTR_GESTURE_TYPE).isEqualTo("ui.gesture.type")
    }

    @Test
    fun `interaction kinds use the canonical vocabulary`() {
        assertThat(INTERACTION_TYPE_TOGGLE).isEqualTo("toggle")
        assertThat(INTERACTION_TYPE_SLIDER).isEqualTo("slider")
    }

    @Test
    fun `gesture vocabulary includes the drag kind`() {
        assertThat(GestureType.DRAG.value).isEqualTo("drag")
    }

    @Test
    fun `slider control kind and value use the canonical wire keys`() {
        assertThat(WIDGET_TYPE_SLIDER).isEqualTo("slider")
        assertThat(ATTR_CONTROL_VALUE).isEqualTo("ui.control.value.value")
    }

    /**
     * A slider carries a position, not a choice among siblings, so selection mode must stay absent
     * rather than being given some default. Pinned so nobody "completes the table" later.
     */
    @Test
    fun `slider has no selection mode`() {
        assertThat(resolveSelectionMode(WIDGET_TYPE_SLIDER)).isNull()
    }

    /**
     * The derivation itself is the contract, not just the key: a dashboard grouping by
     * `interaction.type` depends on which widget kinds collapse into `toggle` and which fall
     * through to the gesture. Pinned per kind so a change to either set is deliberate.
     */
    @Test
    fun `interaction type is resolved from the widget kind`() {
        assertThat(resolveInteractionType(WIDGET_TYPE_SWITCH, GestureType.TAP)).isEqualTo("toggle")
        assertThat(resolveInteractionType(WIDGET_TYPE_CHECKBOX, GestureType.TAP)).isEqualTo("toggle")
        assertThat(resolveInteractionType(WIDGET_TYPE_RADIO, GestureType.TAP)).isEqualTo("toggle")
        assertThat(resolveInteractionType(WIDGET_TYPE_TOGGLE, GestureType.TAP)).isEqualTo("toggle")
    }

    /**
     * A toggle reports `toggle` no matter which gesture flipped it — the whole point of splitting
     * the two keys. If this ever returns `long_press`, the derivation has regressed to mirroring
     * the gesture.
     */
    /**
     * Both a drag along the control and a tap-seek onto it set a value, so both are `slider`. The
     * gesture that produced it stays readable on `ui.gesture.type`.
     */
    @Test
    fun `interaction type is slider for a range control regardless of gesture`() {
        assertThat(resolveInteractionType(WIDGET_TYPE_SLIDER, GestureType.DRAG)).isEqualTo("slider")
        assertThat(resolveInteractionType(WIDGET_TYPE_SLIDER, GestureType.TAP)).isEqualTo("slider")
    }

    @Test
    fun `interaction type ignores the gesture for toggle controls`() {
        assertThat(resolveInteractionType(WIDGET_TYPE_SWITCH, GestureType.LONG_PRESS)).isEqualTo("toggle")
    }

    @Test
    fun `interaction type falls back to the gesture for non-toggle controls`() {
        assertThat(resolveInteractionType(WIDGET_TYPE_BUTTON, GestureType.TAP)).isEqualTo("tap")
        assertThat(resolveInteractionType(WIDGET_TYPE_BUTTON, GestureType.LONG_PRESS)).isEqualTo("long_press")
        assertThat(resolveInteractionType(WIDGET_TYPE_TEXT, GestureType.TAP)).isEqualTo("tap")
    }

    /**
     * Tabs and dropdowns are single-choice, but selecting from them is canonical's `menu_select`,
     * which this module cannot detect (a dropdown's options live in an unwrappable `PopupWindow`).
     * They must keep reporting the gesture rather than claiming an unobserved interaction.
     */
    @Test
    fun `interaction type does not claim a selection for tabs and dropdowns`() {
        assertThat(resolveInteractionType(WIDGET_TYPE_TAB, GestureType.TAP)).isEqualTo("tap")
        assertThat(resolveInteractionType(WIDGET_TYPE_DROPDOWN, GestureType.TAP)).isEqualTo("tap")
    }

    /**
     * The gesture key deliberately does not take the `app.*` prefix that `app.widget.type` and
     * `app.widget.source` carry. Those are legacy platform wire names the module is moving away
     * from (see [ATTR_CONTROL_TYPE]); a new key adopting that prefix would reverse that direction.
     */
    @Test
    fun `gesture type is not emitted under the legacy app prefix`() {
        assertThat(ATTR_GESTURE_TYPE).isNotEqualTo("app.gesture.type")
    }

    /**
     * `interaction.type` and `ui.gesture.type` currently carry the same value, but they are
     * separate contracts: `interaction.type` is scheduled to report control-derived kinds
     * (`toggle`, `slider`), while `ui.gesture.type` must keep answering "what did the finger do".
     * Pinned so a future change cannot quietly collapse them back into one key.
     */
    @Test
    fun `gesture type and interaction type are distinct keys`() {
        assertThat(ATTR_GESTURE_TYPE).isNotEqualTo(ATTR_INTERACTION_TYPE)
    }

    /**
     * The placeholders substituted for calendar controls, and the Material resource names the
     * month/year rules key on. All five are wire-visible strings or undocumented Material
     * internals: if Material renames a resource the suppression silently stops and dates resurface,
     * so the values are pinned here to make any change deliberate rather than invisible.
     */
    @Test
    fun `calendar placeholders and the resource names they key on are pinned`() {
        assertThat(ViewTapTargetDetector.CALENDAR_DAY_LABEL).isEqualTo("calendar day")
        assertThat(ViewTapTargetDetector.CALENDAR_MONTH_LABEL).isEqualTo("calendar month")
        assertThat(ViewTapTargetDetector.CALENDAR_YEAR_LABEL).isEqualTo("calendar year")
        assertThat(ViewTapTargetDetector.RES_NAME_CALENDAR_MONTH_TOGGLE).isEqualTo("month_navigation_fragment_toggle")
        assertThat(ViewTapTargetDetector.RES_NAME_CALENDAR_YEAR_FRAME).isEqualTo("mtrl_calendar_year_selector_frame")
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
