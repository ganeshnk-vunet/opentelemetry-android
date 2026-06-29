/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ActionSummarySpanExporterTest {
    private lateinit var delegate: InMemorySpanExporter
    private lateinit var exporter: ActionSummarySpanExporter

    @BeforeEach
    fun setUp() {
        delegate = InMemorySpanExporter.create()
        exporter = ActionSummarySpanExporter(delegate)
    }

    @Test
    fun addsSummaryToSupportedSpan() {
        val span =
            TestSpanHelper.span(
                "ui.click",
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "button",
                    AttributeKey.stringKey("app.widget.name"), "OK",
                    RumConstants.SCREEN_NAME_KEY, "DialogScreen",
                ),
            )

        exporter.export(listOf(span))

        val exported = delegate.finishedSpanItems
        assertThat(exported).hasSize(1)
        assertThat(exported[0].attributes.get(RumConstants.APP_ACTION_SUMMARY_KEY))
            .isEqualTo("Clicked button 'OK' on DialogScreen")
    }

    @Test
    fun passesUnsupportedSpanUnmodified() {
        val span = TestSpanHelper.span("app.metrics", Attributes.empty())

        exporter.export(listOf(span))

        val exported = delegate.finishedSpanItems
        assertThat(exported).hasSize(1)
        assertThat(exported[0].attributes.get(RumConstants.APP_ACTION_SUMMARY_KEY)).isNull()
    }

    @Test
    fun mixedSpanBatch() {
        val clickSpan =
            TestSpanHelper.span(
                "ui.click",
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "button",
                    AttributeKey.stringKey("app.widget.name"), "Save",
                ),
            )
        val metricsSpan = TestSpanHelper.span("app.metrics", Attributes.empty())

        exporter.export(listOf(clickSpan, metricsSpan))

        val exported = delegate.finishedSpanItems
        assertThat(exported).hasSize(2)
        assertThat(exported[0].attributes.get(RumConstants.APP_ACTION_SUMMARY_KEY))
            .isEqualTo("Clicked button 'Save'")
        assertThat(exported[1].attributes.get(RumConstants.APP_ACTION_SUMMARY_KEY))
            .isNull()
    }

    @Test
    fun delegatesFlush() {
        val result = exporter.flush()
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun delegatesShutdown() {
        val result = exporter.shutdown()
        assertThat(result.isSuccess).isTrue()
    }
}
