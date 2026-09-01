/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.view.MotionEvent
import io.opentelemetry.android.instrumentation.hybrid.click.shared.InteractionType
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
            .isEqualTo(InteractionType.TAP)
    }

    @Test
    fun emits_long_press_when_held_past_the_timeout() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 103f, 204f, 1_900L))
            .isEqualTo(InteractionType.LONG_PRESS)
    }

    @Test
    fun emits_long_press_exactly_at_the_timeout() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_500L))
            .isEqualTo(InteractionType.LONG_PRESS)
    }

    @Test
    fun emits_tap_just_below_the_timeout() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_499L))
            .isEqualTo(InteractionType.TAP)
    }

    @Test
    fun does_not_emit_click_for_drag_scroll_gesture() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_MOVE, 120f, 230f, 1_050L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 120f, 230f, 1_100L)).isNull()
    }

    /** A slow drag leaves the slop, so it is neither a tap nor a long press. */
    @Test
    fun does_not_emit_long_press_for_a_slow_drag() {
        val classifier = classifier()

        classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)
        classifier.classify(MotionEvent.ACTION_MOVE, 120f, 230f, 1_400L)

        assertThat(classifier.classify(MotionEvent.ACTION_UP, 120f, 230f, 1_900L)).isNull()
    }

    @Test
    fun does_not_emit_click_after_cancel() {
        val classifier = classifier()

        assertThat(classifier.classify(MotionEvent.ACTION_DOWN, 100f, 200f, 1_000L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_CANCEL, 100f, 200f, 1_050L)).isNull()
        assertThat(classifier.classify(MotionEvent.ACTION_UP, 100f, 200f, 1_100L)).isNull()
    }
}
