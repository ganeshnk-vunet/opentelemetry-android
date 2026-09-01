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
 * A gesture qualifies only when it reaches [MotionEvent.ACTION_UP] without moving beyond
 * [touchSlopPx] from the original [MotionEvent.ACTION_DOWN] position. A qualifying gesture is then
 * split by how long the pointer was down: below [longPressTimeoutMs] it is an
 * [InteractionType.TAP], at or above it an [InteractionType.LONG_PRESS].
 *
 * The kind is decided at ACTION_UP from the elapsed press duration, so it describes the gesture the
 * user performed. That is not always the gesture the *app* acted on: Android delivers `onLongClick`
 * at the timeout while the finger is still down and then suppresses the click, but only for targets
 * that actually handle long clicks. A slow press on a target without a long-click handler is still
 * reported as [InteractionType.LONG_PRESS] even though the app treated it as an ordinary click.
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

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L
    private var hasActiveGesture: Boolean = false
    private var isTapCandidate: Boolean = false

    /**
     * Consumes a [MotionEvent] and returns the interaction kind only when it ends a qualifying
     * gesture, or `null` for every other event.
     */
    fun classify(event: MotionEvent?): InteractionType? {
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
    ): InteractionType? =
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
                val qualifies =
                    hasActiveGesture &&
                        isTapCandidate &&
                        !isOutsideTapSlop(x, y)
                val pressDurationMs = eventTimeMs - downTimeMs
                reset()
                when {
                    !qualifies -> null
                    pressDurationMs >= longPressTimeoutMs -> InteractionType.LONG_PRESS
                    else -> InteractionType.TAP
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
