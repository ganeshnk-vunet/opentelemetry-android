/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click.view

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Covers taps on `ListView` / `GridView` rows.
 *
 * These lists put every row at the same address before this was handled: an [android.widget.AdapterView]
 * marks *itself* clickable and dispatches item clicks internally, so its rows are not clickable and
 * the deepest clickable target for any tap in the list was the list container. Every row therefore
 * produced an identical span — same id, same type — labelled from whichever row came first in the
 * descendant search, no matter which row was tapped.
 *
 * The label assertions deliberately check the **last** row. Asserting the first would pass even with
 * the bug present, since the first row is exactly what the broken path returned.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AdapterViewTapTargetTest {
    private lateinit var context: Context
    private val detector = ViewTapTargetDetector()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** The framework facts the whole fix rests on; if these change, the exception is dead code. */
    @Test
    fun `list containers are clickable while their rows are not`() {
        assertThat(ListView(context).isClickable).isTrue()
        assertThat(GridView(context).isClickable).isTrue()
        assertThat(TextView(context).isClickable).isFalse()
    }

    @Test
    fun `tapping a grid row resolves that row, not the first one`() {
        val root = gridOf(ROWS)
        val (x, y) = centreOf(rowAt(root, ROWS.lastIndex))

        val target = detector.findTapTarget(root, x, y)

        assertThat(target?.label).isEqualTo(ROWS.last())
    }

    @Test
    fun `tapping a list row resolves that row, not the first one`() {
        val root = listOf(ROWS)
        val (x, y) = centreOf(rowAt(root, ROWS.lastIndex))

        val target = detector.findTapTarget(root, x, y)

        assertThat(target?.label).isEqualTo(ROWS.last())
    }

    /**
     * Identity, not just the label. Two rows of the same list must not collapse onto one target —
     * that is what made list taps unattributable, and a label-only fix would have left it.
     */
    @Test
    fun `two rows of the same grid resolve to different targets`() {
        val root = gridOf(ROWS)
        val (firstX, firstY) = centreOf(rowAt(root, 0))
        val (lastX, lastY) = centreOf(rowAt(root, ROWS.lastIndex))

        val first = detector.findTapTarget(root, firstX, firstY)
        val last = detector.findTapTarget(root, lastX, lastY)

        assertThat(first?.label).isEqualTo(ROWS.first())
        assertThat(last?.label).isEqualTo(ROWS.last())
    }

    /**
     * A spinner's only child is its *selected* item's view, not a row, so the spinner itself stays
     * the target. Its actual options live in a `PopupWindow` this module cannot see.
     */
    @Test
    fun `tapping a spinner resolves the spinner, not its selected item view`() {
        val spinner =
            Spinner(context).apply {
                isClickable = true
                contentDescription = "Account"
                adapter = RowAdapter(context, ROWS)
            }
        val root = laidOut(spinner)

        val (x, y) = centreOf(spinner)

        val target = detector.findTapTarget(root, x, y)

        assertThat(target?.label).isEqualTo("Account")
    }

    private fun gridOf(rows: List<String>): View =
        laidOut(
            GridView(context).apply {
                numColumns = rows.size
                adapter = RowAdapter(context, rows)
            },
        )

    private fun listOf(rows: List<String>): View =
        laidOut(
            ListView(context).apply {
                adapter = RowAdapter(context, rows)
            },
        )

    /**
     * Lays [child] out inside a real Activity window.
     *
     * Attaching to a window matters: `hitTest` resolves positions through
     * [View.getLocationInWindow], which reports (0, 0) for every view in a detached hierarchy. A
     * detached tree would make each row appear to sit at the origin, so a coordinate could not pick
     * one row over another and these tests would prove nothing about position.
     */
    private fun laidOut(child: View): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(context)
        root.addView(child, FrameLayout.LayoutParams(SIZE, SIZE))
        activity.setContentView(root)
        val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, SIZE, SIZE)
        return root
    }

    /** Window-space centre of [view], so tests tap where a row actually is rather than guessing. */
    private fun centreOf(view: View): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
    }

    private fun rowAt(
        root: View,
        index: Int,
    ): View = ((root as FrameLayout).getChildAt(0) as ViewGroup).getChildAt(index)

    private class RowAdapter(
        private val context: Context,
        private val rows: List<String>,
    ) : BaseAdapter() {
        override fun getCount() = rows.size

        override fun getItem(position: Int) = rows[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?,
        ): View =
            TextView(context).apply {
                text = rows[position]
                contentDescription = rows[position]
            }
    }

    private companion object {
        const val SIZE = 300
        val ROWS = kotlin.collections.listOf("Row one", "Row two", "Row three")
    }
}
