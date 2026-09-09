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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.datepicker.MaterialCalendarGridView
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * A calendar day's accessibility label is the date itself, so reporting it normally would put the
 * date a user is choosing onto the wire — precisely what confirming a picker avoids by reporting a
 * relative offset instead.
 *
 * The suppression is deliberately narrow: it must not reach ordinary list rows, whose labels are the
 * reason click telemetry is worth anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class CalendarDayLabelTest {
    private lateinit var context: Context
    private val detector = ViewTapTargetDetector()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a calendar day never reports the date it represents`() {
        val root = gridOf(MaterialCalendarGridView(context))

        val target = detector.findTapTarget(root, centreX(root), centreY(root))

        assertThat(target?.label).isEqualTo("calendar day")
        assertThat(target?.label).doesNotContain("September")
    }

    /**
     * The guard that keeps this from becoming a blanket label suppressor. An ordinary list row must
     * still report its own text — that is the whole value of the signal.
     */
    @Test
    fun `an ordinary grid row keeps its label`() {
        val root = gridOf(GridView(context))

        val target = detector.findTapTarget(root, centreX(root), centreY(root))

        assertThat(target?.label).isEqualTo(DAYS.first())
    }

    /**
     * The guard on the name-based rules: a button that merely *looks* like a calendar control, with
     * no matching resource name, keeps its own label. Without this, any app button whose text
     * happened to read like a month could be silenced.
     *
     * The month-selector and year-cell rules themselves match on Material's private resource entry
     * names, which cannot be synthesized here — Robolectric resolves names from this module's own
     * resources, and inventing Material ids would test the stand-in rather than the rule. Those two
     * are verified on a real Material date picker on device instead; what is pinned here is that the
     * rules stay narrow.
     */
    @Test
    fun `an ordinary button with a date-like label is untouched`() {
        val button = Button(context).apply { isClickable = true; text = "September 2026" }
        val root = laidOut(button)

        val target = detector.findTapTarget(root, centreX(root, button), centreY(root, button))

        assertThat(target?.label).isEqualTo("September 2026")
    }

    private fun gridOf(grid: GridView): View {
        grid.numColumns = 1
        grid.adapter = DayAdapter(context, DAYS)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(context)
        root.addView(grid, FrameLayout.LayoutParams(SIZE, SIZE))
        activity.setContentView(root)
        val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, SIZE, SIZE)
        return root
    }

    private fun firstCell(root: View): View = ((root as FrameLayout).getChildAt(0) as ViewGroup).getChildAt(0)

    private fun centreX(
        root: View,
        view: View = firstCell(root),
    ): Float {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[0] + view.width / 2f
    }

    private fun centreY(
        root: View,
        view: View = firstCell(root),
    ): Float {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[1] + view.height / 2f
    }

    /** Wraps [child] in a laid-out Activity window, without the adapter machinery. */
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

    private class DayAdapter(
        private val context: Context,
        private val days: List<String>,
    ) : BaseAdapter() {
        override fun getCount() = days.size

        override fun getItem(position: Int) = days[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?,
        ): View =
            TextView(context).apply {
                text = days[position]
                contentDescription = days[position]
            }
    }

    private companion object {
        const val SIZE = 300
        val DAYS = listOf("Friday, September 4", "Saturday, September 5")

    }
}
