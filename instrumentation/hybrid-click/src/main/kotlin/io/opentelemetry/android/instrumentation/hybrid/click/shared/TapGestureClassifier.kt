/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.shared

import android.view.MotionEvent
import kotlin.math.pow

/**
 * Classifies pointer sequences as either tap-like gestures or non-tap gestures.
 *
 * A gesture that reaches [MotionEvent.ACTION_UP] without moving beyond [touchSlopPx] from the
 * original [MotionEvent.ACTION_DOWN] position is tap-like, and is split by how long the pointer was
 * down: below [longPressTimeoutMs] it is a [GestureType.TAP], at or above it a
 * [GestureType.LONG_PRESS]. One that moves further is a [GestureType.DRAG], reported rather than
 * swallowed so the caller can judge it against the control underneath — dragging a slider is a real
 * interaction, scrolling a list is not. The classifier stays target-agnostic: it says what the
 * pointer did, never whether that deserves a span.
 *
 * The kind is decided at ACTION_UP from the elapsed press duration, so it describes the gesture the
 * user performed. That is not always the gesture the *app* acted on: Android delivers `onLongClick`
 * at the timeout while the finger is still down and then suppresses the click, but only for targets
 * that actually handle long clicks. A slow press on a target without a long-click handler is still
 * reported as [GestureType.LONG_PRESS] even though the app treated it as an ordinary click.
 */
internal class TapGestureClassifier {
    /**
     * Maximum movement allowed between down and up for a gesture to still count as a tap.
     */
    var touchSlopPx: Float = DEFAULT_TOUCH_SLOP_PX

    /**
     * Press duration at or above which a qualifying gesture is reported as a long press. Seeded
     * from `ViewConfiguration.getLongPressTimeout()` when tracking starts.
     */
    var longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_TIMEOUT_MS

    /**
     * Where the gesture started. Deliberately readable after [classify] returns: [reset] clears only
     * the in-progress flags, so a caller handling a [GestureType.DRAG] can resolve the target at the
     * point the finger went *down*. That matters because a drag routinely ends well outside the
     * control it started on — a slider dragged to its end, for instance.
     */
    var downX: Float = 0f
        private set

    var downY: Float = 0f
        private set

    private var downTimeMs: Long = 0L
    private var hasActiveGesture: Boolean = false
    private var isTapCandidate: Boolean = false

    /**
     * Consumes a [MotionEvent] and returns the interaction kind only when it ends a qualifying
     * gesture, or `null` for every other event.
     */
    fun classify(event: MotionEvent?): GestureType? {
        if (event == null) {
            return null
        }
        return classify(event.actionMasked, event.x, event.y, event.eventTime)
    }

    /**
     * Test-friendly overload that accepts primitive event data instead of a [MotionEvent].
     *
     * [eventTimeMs] uses the same `SystemClock.uptimeMillis()` base as [MotionEvent.getEventTime].
     */
    fun classify(
        actionMasked: Int,
        x: Float,
        y: Float,
        eventTimeMs: Long,
    ): GestureType? =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                downTimeMs = eventTimeMs
                hasActiveGesture = true
                isTapCandidate = true
                null
            }

            MotionEvent.ACTION_MOVE -> {
                if (hasActiveGesture && isTapCandidate && isOutsideTapSlop(x, y)) {
                    isTapCandidate = false
                }
                null
            }

            MotionEvent.ACTION_CANCEL -> {
                reset()
                null
            }

            MotionEvent.ACTION_UP -> {
                // "No active gesture" and "active but moved too far" must stay distinct: collapsing
                // them would report a DRAG for the spurious ACTION_UP that follows an ACTION_CANCEL,
                // inventing an interaction out of a gesture the platform already abandoned.
                val hadActiveGesture = hasActiveGesture
                val stayedWithinSlop = isTapCandidate && !isOutsideTapSlop(x, y)
                val pressDurationMs = eventTimeMs - downTimeMs
                reset()
                when {
                    !hadActiveGesture -> null
                    !stayedWithinSlop -> GestureType.DRAG
                    pressDurationMs >= longPressTimeoutMs -> GestureType.LONG_PRESS
                    else -> GestureType.TAP
                }
            }

            else -> null
        }

    private fun isOutsideTapSlop(x: Float, y: Float): Boolean {
        val distanceSquared = (x - downX).pow(2) + (y - downY).pow(2)
        val slopSquared = touchSlopPx.pow(2)
        return distanceSquared > slopSquared
    }

    /**
     * Clears all in-progress gesture state, used on teardown and cancelled gestures.
     */
    fun reset() {
        hasActiveGesture = false
        isTapCandidate = false
    }

    private companion object {
        const val DEFAULT_TOUCH_SLOP_PX = 8f

        /** Matches the platform default; the real value is seeded when tracking starts. */
        const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L
    }
}
