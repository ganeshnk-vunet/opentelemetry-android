/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.view.MotionEvent
import io.opentelemetry.android.instrumentation.hybrid.click.shared.GestureType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapGestureClassifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TapGestureClassifierTest {
    private fun classifier() =
        TapGestureClassifier().apply {
            touchSlopPx = 8f
            longPressTimeoutMs = 500L
        }

    @Test
    fun emits_tap_for_valid_tap() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_MOVE, 103f, 204f, 1_050L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 103f, 204f, 1_100L))
            .isEqualTo(GestureType.TAP)
    }

    @Test
    fun emits_long_press_when_held_past_the_timeout() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 103f, 204f, 1_900L))
            .isEqualTo(GestureType.LONG_PRESS)
    }

    @Test
    fun emits_long_press_exactly_at_the_timeout() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_500L))
            .isEqualTo(GestureType.LONG_PRESS)
    }

    @Test
    fun emits_tap_just_below_the_timeout() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_499L))
            .isEqualTo(GestureType.TAP)
    }

    /** Leaving the slop makes it a drag, not a tap — the caller decides whether that means anything. */
    @Test
    fun emits_drag_for_a_scroll_gesture() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_MOVE, 120f, 230f, 1_050L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 120f, 230f, 1_100L))
            .isEqualTo(GestureType.DRAG)
    }

    /** A slow drag left the slop, so duration is irrelevant: it is a drag, not a long press. */
    @Test
    fun emits_drag_for_a_slow_drag() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)
        classifier.classify(MotionEvent.ACTION_MOVE, 120f, 230f, 1_400L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 120f, 230f, 1_900L))
            .isEqualTo(GestureType.DRAG)
    }

    /**
     * The regression test for conflating "no active gesture" with "moved too far": a cancelled
     * gesture must stay silent rather than being reported as a drag. Android sends ACTION_CANCEL
     * whenever a parent steals the gesture, which is common, so getting this wrong would fabricate
     * interactions constantly.
     */
    @Test
    fun does_not_emit_click_after_cancel() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_CANCEL, 100f, 200f, 1_050L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_100L)).isNull()
    }

    /** An ACTION_UP with no preceding ACTION_DOWN is not a gesture at all. */
    @Test
    fun does_not_emit_for_a_spurious_up_with_no_down() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_100L)).isNull()
    }

    /**
     * Pins the temporal coupling the whole drag path rests on: the emitter reads the down point
     * *after* `classify` has returned, to resolve the control the drag started on. If `reset` ever
     * cleared these too, every slider drag would resolve against (0, 0) instead.
     */
    @Test
    fun retains_the_down_point_after_the_gesture_ends() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)
        classifier.classify(MotionEvent.ACTION_MOVE, 300f, 400f, 1_050L)
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 300f, 400f, 1_100L))
            .isEqualTo(GestureType.DRAG)

        assertThat(classifier.downX).isEqualTo(100f)
        assertThat(classifier.downY).isEqualTo(200f)
    }
}
