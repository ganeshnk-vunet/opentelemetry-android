/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.view

import io.opentelemetry.android.instrumentation.hybrid.click.shared.ControlValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the two halves of date-picker reading that need no fragment machinery: the duck-typing that
 * decides whether an object is a picker at all, and the day arithmetic.
 *
 * The duck-typing is tested against plain fakes precisely because it is *not* matched on
 * `MaterialDatePicker`'s qualified name — Material is not a dependency of this module, so a
 * name-matched implementation could not be unit-tested here at all.
 */
class DatePickerSelectionReaderTest {
    @Test
    fun `single date selection becomes a day offset`() {
        val selection = SingleSelection(NOW - 30 * DAY)

        assertThat(DatePickerSelectionReader.selectionOf(selection, NOW))
            .isEqualTo(ControlValue.SelectedDate(-30L))
    }

    @Test
    fun `a future date is a positive offset`() {
        val selection = SingleSelection(NOW + 7 * DAY)

        assertThat(DatePickerSelectionReader.selectionOf(selection, NOW))
            .isEqualTo(ControlValue.SelectedDate(7L))
    }

    @Test
    fun `today is a zero offset`() {
        assertThat(DatePickerSelectionReader.selectionOf(SingleSelection(NOW), NOW))
            .isEqualTo(ControlValue.SelectedDate(0L))
    }

    /**
     * A birth-date picker makes pre-1970 an ordinary case, not a curiosity. Integer division rounds
     * toward zero, so without an explicit floor a negative instant lands a day late — this fails if
     * the hand-rolled floor in `epochDay` is replaced by plain division.
     */
    @Test
    fun `a pre-1970 date floors rather than truncating`() {
        // 1969-12-31T12:00Z: half a day before the epoch, so epoch-day -1, not 0.
        val beforeEpoch = -12 * 60 * 60 * 1000L

        assertThat(DatePickerSelectionReader.dayOffset(beforeEpoch, 0L)).isEqualTo(-1L)
    }

    @Test
    fun `a range selection reports both ends`() {
        val selection = RangeSelection(NOW - 30 * DAY, NOW)

        assertThat(DatePickerSelectionReader.selectionOf(selection, NOW))
            .isEqualTo(ControlValue.SelectedDateRange(startDayOffset = -30L, endDayOffset = 0L))
    }

    /** A range picker can be confirmed with only one side chosen. */
    @Test
    fun `a half-open range reports only the end it has`() {
        val selection = RangeSelection(NOW - 5 * DAY, null)

        assertThat(DatePickerSelectionReader.selectionOf(selection, NOW))
            .isEqualTo(ControlValue.SelectedDateRange(startDayOffset = -5L, endDayOffset = null))
    }

    /** Pairs are duck-typed by field *or* getter, so androidx, android and kotlin pairs all work. */
    @Test
    fun `a getter-based pair is read like a field-based one`() {
        val selection = GetterRangeSelection(NOW - 2 * DAY, NOW + 2 * DAY)

        assertThat(DatePickerSelectionReader.selectionOf(selection, NOW))
            .isEqualTo(ControlValue.SelectedDateRange(startDayOffset = -2L, endDayOffset = 2L))
    }

    @Test
    fun `no selection yet yields nothing`() {
        assertThat(DatePickerSelectionReader.selectionOf(SingleSelection(null), NOW)).isNull()
    }

    /** The check that keeps every other DialogFragment out of the date-picker path. */
    @Test
    fun `an owner without getSelection is not a picker`() {
        assertThat(DatePickerSelectionReader.selectionOf(NotAPicker(), NOW)).isNull()
        assertThat(DatePickerSelectionReader.selectionOf(null, NOW)).isNull()
    }

    /** A custom DateSelector can return anything; an uninterpretable shape must not guess. */
    @Test
    fun `an unrecognised selection shape yields nothing`() {
        assertThat(DatePickerSelectionReader.selectionOf(SingleSelection("2026-01-01"), NOW)).isNull()
    }

    class SingleSelection(
        private val selection: Any?,
    ) {
        fun getSelection(): Any? = selection
    }

    class RangeSelection(
        start: Long?,
        end: Long?,
    ) {
        private val selection = FieldPair(start, end)

        fun getSelection(): Any = selection
    }

    /** Mirrors `androidx.core.util.Pair`, whose components are public fields. */
    class FieldPair(
        @JvmField val first: Long?,
        @JvmField val second: Long?,
    )

    class GetterRangeSelection(
        start: Long?,
        end: Long?,
    ) {
        private val selection = GetterPair(start, end)

        fun getSelection(): Any = selection
    }

    /** Mirrors `kotlin.Pair`, whose components are reachable only through getters. */
    class GetterPair(
        val first: Long?,
        val second: Long?,
    )

    class NotAPicker

    private companion object {
        const val DAY = 86_400_000L

        /** An exact UTC midnight, matching what a Material date picker reports. */
        const val NOW = 1_767_225_600_000L
    }
}
