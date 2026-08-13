/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

internal class AppStartSpanTrackerTest {
    private val tracker = AppStartSpanTracker()
    private val context = mockk<Context>()

    @BeforeEach
    @AfterEach
    fun reset() {
        AppStartSpans.clear()
    }

    @Test
    fun `publishes app start span on start`() {
        val span = appStartSpan(SPAN_ID_A)

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isSameAs(span)
    }

    @Test
    fun `ignores spans that are not app start`() {
        val span = mockk<ReadWriteSpan>()
        every { span.name } returns "ui.navigation"

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isNull()
    }

    @Test
    fun `clears when the published span ends`() {
        val span = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, span)

        tracker.onEnd(endedSpan(SPAN_ID_A))

        assertThat(AppStartSpans.current).isNull()
    }

    /**
     * A warm start can open while a previous app.start is still closing. Clearing on
     * the wrong span would blind wrappers for the rest of the live span's window.
     */
    @Test
    fun `keeps the live span when a different app start span ends`() {
        val live = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, live)

        tracker.onEnd(endedSpan(SPAN_ID_A))

        assertThat(AppStartSpans.current).isSameAs(live)
    }

    /** Cold, warm and hot all carry the same span name, so all three are tracked. */
    @Test
    fun `tracks a later app start span, covering warm and hot starts`() {
        val cold = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, cold)
        tracker.onEnd(endedSpan(SPAN_ID_A))

        val warm = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, warm)

        assertThat(AppStartSpans.current).isSameAs(warm)
    }

    /**
     * Cold start stays open until the first frame. A warm start that opens in that
     * window (first activity recreated before TTID) must not steal the holder —
     * wrappers would otherwise stamp onto the short warm span, then see null while
     * cold is still recording.
     */
    @Test
    fun `does not overwrite a live span`() {
        val cold = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, cold)

        val warm = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, warm)

        assertThat(AppStartSpans.current).isSameAs(cold)

        tracker.onEnd(endedSpan(SPAN_ID_B))
        assertThat(AppStartSpans.current).isSameAs(cold)

        tracker.onEnd(endedSpan(SPAN_ID_A))
        assertThat(AppStartSpans.current).isNull()
    }

    /**
     * The tracker is notified after the span ends, so a reader can reach the global
     * in between. An ended span silently discards writes, so it must not be handed out.
     */
    @Test
    fun `hides a span that has already ended`() {
        val span = appStartSpan(SPAN_ID_A, hasEnded = true)

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isNull()
    }

    /**
     * The tracker is notified after the span ends, so a reader can reach the global
     * in between. Dropping the stale reference lets a later start publish.
     */
    @Test
    fun `drops a span that ends after it was published`() {
        val span = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, span)
        every { span.hasEnded() } returns true

        assertThat(AppStartSpans.current).isNull()

        val warm = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, warm)

        assertThat(AppStartSpans.current).isSameAs(warm)
    }

    /**
     * AppStartupTimer discards its span without ending it when UI init arrives too
     * late — the normal outcome when Android starts the process in the background and
     * the user opens the app minutes later. No end means no onEnd, so nothing would
     * ever clear this; without the age bound the stale span would swallow every
     * wrapper write for the rest of the process while still looking like a success.
     */
    @Test
    fun `hides a span abandoned without ending`() {
        val span =
            appStartSpan(
                SPAN_ID_A,
                latencyNanos = TimeUnit.MINUTES.toNanos(5),
            )

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isNull()
    }

    /** Observing an abandoned span must drop it so a later start can publish. */
    @Test
    fun `a later start publishes after an abandoned span is observed`() {
        tracker.onStart(
            context,
            appStartSpan(SPAN_ID_A, latencyNanos = TimeUnit.MINUTES.toNanos(5)),
        )
        assertThat(AppStartSpans.current).isNull()

        val warm = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, warm)

        assertThat(AppStartSpans.current).isSameAs(warm)
    }

    @Test
    fun `keeps a span inside the startup window`() {
        val span =
            appStartSpan(
                SPAN_ID_A,
                latencyNanos = TimeUnit.SECONDS.toNanos(2),
            )

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isSameAs(span)
    }

    private fun appStartSpan(
        spanId: String,
        hasEnded: Boolean = false,
        latencyNanos: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): ReadWriteSpan {
        val span = mockk<ReadWriteSpan>()
        every { span.name } returns RumConstants.APP_START_SPAN_NAME
        every { span.spanContext } returns spanContext(spanId)
        every { span.hasEnded() } returns hasEnded
        every { span.latencyNanos } returns latencyNanos
        return span
    }

    private fun endedSpan(spanId: String): ReadableSpan {
        val span = mockk<ReadableSpan>()
        every { span.spanContext } returns spanContext(spanId)
        return span
    }

    private fun spanContext(spanId: String): SpanContext =
        SpanContext.create(TRACE_ID, spanId, TraceFlags.getSampled(), TraceState.getDefault())

    private companion object {
        const val TRACE_ID = "00000000000000000000000000000042"
        const val SPAN_ID_A = "000000000000000a"
        const val SPAN_ID_B = "000000000000000b"
    }
}
