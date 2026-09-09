/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.view

import android.view.View
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ControlValue

/**
 * Recognizes a Material date picker's confirm button and reads what was chosen.
 *
 * ## Why the confirm button
 * A `MaterialDatePicker` is a `DialogFragment`, so its window is already tracked and its confirm tap
 * already produces a span — as an anonymous `button` labelled with a localized "OK". Nothing needs
 * intercepting; the tap only needs recognizing. The choice becomes final at that tap, which makes it
 * the right place to report the selection.
 *
 * ## How it is recognized
 * Two stages, cheapest first. The tag check is a field read, so ordinary taps pay nothing for this
 * path; only a match escalates to resolving the owning fragment.
 *
 * The tag is matched as a **string literal**. `MaterialDatePicker.CONFIRM_BUTTON_TAG` is
 * package-private and typed `Object`, but holds the interned string `"CONFIRM_BUTTON_TAG"`, set via
 * `Button.setTag` — so this needs no dependency on `com.google.android.material`, which this module
 * deliberately does not have. It does couple us to an undocumented internal, which is why the
 * literal is pinned by a wire-key test rather than left to fail silently.
 *
 * The owning fragment is then **duck-typed** on a public no-arg `getSelection()`, rather than matched
 * on `MaterialDatePicker`'s qualified name. That handles app subclasses for free (the class is not
 * final) and keeps the whole thing unit-testable, since Material cannot be a test dependency here.
 *
 * ## What is read
 * `MaterialDatePicker.getSelection()` is public and returns UTC-midnight epoch millis: a `Long` for a
 * single-date picker, a pair for a range, or `null` before anything is chosen. That runtime type is
 * the *only* way to tell single from range — `getDateSelector()` is private, and `getInputMode()`
 * distinguishes calendar from keyboard entry, not arity.
 */
internal object DatePickerSelectionReader {
    /**
     * The tag `MaterialDatePicker` puts on its confirm button.
     *
     * Matching on the button's resource-id name (`confirm_button`) was rejected: apps commonly define
     * an id by that name themselves, so it would misreport ordinary buttons as date pickers.
     */
    const val CONFIRM_BUTTON_TAG = "CONFIRM_BUTTON_TAG"

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Cheap pre-filter: is this the confirm button of a Material date picker? */
    fun isDatePickerConfirmButton(view: View): Boolean = view.tag == CONFIRM_BUTTON_TAG

    /**
     * Resolves the picker owning [view] and converts its selection, or `null` when [view] is not a
     * date picker's confirm button or nothing was chosen.
     *
     * Everything is wrapped: `androidx.fragment` is a `compileOnly` dependency, so on an app without
     * it the fragment lookup raises `NoClassDefFoundError`, and `findFragment` itself throws when the
     * view has no owning fragment. Both mean "not a date picker", not "fail the touch".
     */
    fun read(
        view: View,
        nowMillis: Long,
    ): ControlValue? {
        if (!isDatePickerConfirmButton(view)) {
            return null
        }
        val owner = runCatching { androidx.fragment.app.FragmentManager.findFragment<androidx.fragment.app.Fragment>(view) }.getOrNull()
        return selectionOf(owner, nowMillis)
    }

    /**
     * Converts [owner]'s `getSelection()` result into a [ControlValue], or `null` when [owner] is not
     * a picker or has no selection yet.
     *
     * Split out from [read] so the duck-typing and the arithmetic are testable against a plain fake,
     * with no fragment machinery involved — the same seam as `normalizedValueOf` on the Compose side.
     */
    fun selectionOf(
        owner: Any?,
        nowMillis: Long,
    ): ControlValue? {
        if (owner == null) {
            return null
        }
        val selection =
            runCatching {
                owner.javaClass.getMethod("getSelection").invoke(owner)
            }.getOrNull() ?: return null
        return controlValueOf(selection, nowMillis)
    }

    /**
     * Maps a raw selection to a [ControlValue]: a `Long` is a single date, a pair-shaped object is a
     * range, and anything else is a custom `DateSelector` this module cannot interpret.
     *
     * The pair is duck-typed rather than typed as `androidx.core.util.Pair` so `android.util.Pair`
     * and `kotlin.Pair` work too, and so tests can use whichever is convenient.
     */
    private fun controlValueOf(
        selection: Any,
        nowMillis: Long,
    ): ControlValue? {
        (selection as? Long)?.let {
            return ControlValue.SelectedDate(dayOffset(it, nowMillis))
        }
        val start = pairComponent(selection, "first", "getFirst")
        val end = pairComponent(selection, "second", "getSecond")
        if (start == null && end == null) {
            return null
        }
        return ControlValue.SelectedDateRange(
            startDayOffset = start?.let { dayOffset(it, nowMillis) },
            endDayOffset = end?.let { dayOffset(it, nowMillis) },
        )
    }

    /** Reads one half of a pair-shaped object, by public field or by getter, whichever it exposes. */
    private fun pairComponent(
        selection: Any,
        fieldName: String,
        getterName: String,
    ): Long? {
        val type = selection.javaClass
        val byField = runCatching { type.getField(fieldName).get(selection) }.getOrNull()
        val value = byField ?: runCatching { type.getMethod(getterName).invoke(selection) }.getOrNull()
        return value as? Long
    }

    /**
     * Whole days from today to [millis], negative for the past.
     *
     * Both sides reduce to an epoch-day number, so this needs no `Calendar`, no formatter and no
     * timezone handling — which also keeps it clear of `java.time`, unavailable here because core
     * library desugaring is not enabled for library modules.
     */
    fun dayOffset(
        millis: Long,
        nowMillis: Long,
    ): Long = epochDay(millis) - epochDay(nowMillis)

    /**
     * Epoch-day number for [millis], flooring rather than truncating.
     *
     * Integer division rounds toward zero, so a pre-1970 instant — an ordinary birth date — would
     * otherwise land a day late. `Math.floorDiv` is API 24 against this module's `minSdk` of 23, so
     * the floor is done by hand.
     */
    private fun epochDay(millis: Long): Long {
        val day = millis / MILLIS_PER_DAY
        return if (millis < 0 && millis % MILLIS_PER_DAY != 0L) day - 1 else day
    }
}
