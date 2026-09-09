/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

/**
 * The post-gesture value of a control, read on a later main-loop tick.
 *
 * A sealed type rather than one nullable field per value kind so [ClickEventGenerator] keeps a
 * single deferred-read-and-end path: the widget decides *which* value it has, the emitter only
 * decides which attribute to write.
 */
internal sealed interface ControlValue {
    /** On/off state of a toggle, written to `ui.control.value.checked`. */
    data class Checked(
        val checked: Boolean,
    ) : ControlValue

    /**
     * Position as a percentage (0–100) of the control's own range, written to
     * `ui.control.value.value`.
     *
     * Normalized deliberately, never the underlying value: on a BFSI amount slider the raw number is
     * user-entered financial data, and this module's standing guarantee is that such values are
     * excluded rather than sanitized. A percentage keeps the interaction analytically useful — how
     * far along its range the user pushed it — without putting the amount on the wire.
     */
    data class Percentage(
        val percent: Double,
    ) : ControlValue

    /**
     * Date chosen in a single-date picker, as a whole-day offset from today — written to
     * `ui.control.value.selected_date`.
     *
     * Relative deliberately, never an absolute date: a picked date is user-entered data, and this
     * module's standing rule is that such values are excluded rather than sanitized. An offset still
     * answers the question worth asking of a statement or booking flow — how far back or forward
     * people reach — without putting the date itself on the wire.
     */
    data class SelectedDate(
        val dayOffset: Long,
    ) : ControlValue

    /**
     * Both ends of a chosen date range, as whole-day offsets from today, written to
     * `ui.control.value.start_date` and `.end_date`. Either end may be `null`, because a range
     * picker can be confirmed with only one side chosen.
     *
     * The range *length* — usually the more interesting figure — is `end - start`, so it needs no
     * key of its own.
     */
    data class SelectedDateRange(
        val startDayOffset: Long?,
        val endDayOffset: Long?,
    ) : ControlValue
}

/**
 * Normalized tap target metadata used to build hybrid click spans.
 *
 * [source] identifies where the target came from (`view` or `compose`).
 *
 * [valueProvider] is supplied only when the target actually carries a value — a toggle's checked
 * state or a slider's position. It is a deferred read of the live value rather than a captured one:
 * the span is emitted before the touch is delegated to the underlying widget, so reading lazily (on
 * a later main-loop tick) reports the *resulting* value after the widget has processed the gesture.
 *
 * [interactionKind] overrides the `interaction.type` that would otherwise be derived from [type].
 * It exists for controls whose *interaction* is not inferable from the widget kind: a date picker's
 * confirm button is a plain button, and only the detector — which can see the picker around it —
 * knows the tap means a date selection.
 *
 * [valueIsPreGesture] marks a [valueProvider] that returns a value snapshotted *before* the gesture
 * reached the widget, rather than reading it afterwards. The Compose path has no choice but to do
 * this — a Compose control's value only reaches its semantics after a composition pass, which runs
 * on a frame boundary rather than a message boundary, so a deferred read cannot reliably observe it.
 * Such a value is close enough during a drag (it trails by at most one frame of movement) but
 * entirely wrong for a tap, where nothing has been processed yet, so the emitter records it only for
 * a drag.
 *
 * [isTracking] records whether the widget was actively handling this gesture at the time the target
 * was resolved. It only matters for drags: a slider inside a scrolling container can lose the
 * gesture to its parent — receiving `ACTION_CANCEL` and never seeking — while the window callback
 * still observes the whole `DOWN`…`UP` stream, which would otherwise produce a slider span for a
 * value that never changed.
 */
internal data class TapTarget(
    val source: String,
    val widgetId: String,
    val widgetName: String,
    val label: String,
    val x: Long,
    val y: Long,
    val type: String = WIDGET_TYPE_UNKNOWN,
    val interactionKind: String? = null,
    val isTracking: Boolean = false,
    val valueProvider: (() -> ControlValue?)? = null,
    val valueIsPreGesture: Boolean = false,
)
