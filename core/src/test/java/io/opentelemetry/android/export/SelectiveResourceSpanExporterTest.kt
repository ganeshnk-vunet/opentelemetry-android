/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SelectiveResourceSpanExporterTest {
    private lateinit var delegate: InMemorySpanExporter
    private lateinit var minimalResource: Resource
    private lateinit var fullResource: Resource

    @BeforeEach
    fun setUp() {
        delegate = InMemorySpanExporter.create()
        minimalResource =
            Resource.getDefault().merge(
                Resource.builder().put(ServiceAttributes.SERVICE_NAME, "test-app").build(),
            )
        fullResource =
            minimalResource.merge(
                Resource.builder().put(DeviceIncubatingAttributes.DEVICE_MODEL_NAME, "Pixel").build(),
            )
    }

    @Test
    fun coldAppStartExportsWithFullResource() {
        val underTest = SelectiveResourceSpanExporter(delegate, fullResource)
        val span =
            span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(RumConstants.START_TYPE_KEY, "cold"),
                minimalResource,
            )

        underTest.export(listOf(span))

        assertThat(delegate.finishedSpanItems).hasSize(1)
        assertThat(delegate.finishedSpanItems[0].resource).isEqualTo(fullResource)
    }

    @Test
    fun otherSpansKeepMinimalResource() {
        val underTest = SelectiveResourceSpanExporter(delegate, fullResource)
        val span = span("GET", Attributes.empty(), minimalResource)

        underTest.export(listOf(span))

        assertThat(delegate.finishedSpanItems).hasSize(1)
        assertThat(delegate.finishedSpanItems[0].resource).isEqualTo(minimalResource)
    }

    @Test
    fun warmAppStartKeepsMinimalResource() {
        val underTest = SelectiveResourceSpanExporter(delegate, fullResource)
        val span =
            span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(RumConstants.START_TYPE_KEY, "warm"),
                minimalResource,
            )

        underTest.export(listOf(span))

        assertThat(delegate.finishedSpanItems[0].resource).isEqualTo(minimalResource)
    }

    @Test
    fun onlyFirstColdAppStartGetsFullResource() {
        val underTest = SelectiveResourceSpanExporter(delegate, fullResource)
        val cold =
            span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(RumConstants.START_TYPE_KEY, "cold"),
                minimalResource,
            )
        val coldAgain =
            span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(RumConstants.START_TYPE_KEY, "cold"),
                minimalResource,
            )

        underTest.export(listOf(cold, coldAgain))

        assertThat(delegate.finishedSpanItems).hasSize(2)
        assertThat(delegate.finishedSpanItems[0].resource).isEqualTo(fullResource)
        assertThat(delegate.finishedSpanItems[1].resource).isEqualTo(minimalResource)
    }

    private fun span(
        name: String,
        attributes: Attributes,
        resource: Resource,
    ): SpanData =
        TestSpanHelper.span(name, attributes).let { original ->
            io.opentelemetry.sdk.testing.trace.TestSpanData
                .builder()
                .setName(original.name)
                .setKind(original.kind)
                .setStatus(original.status)
                .setHasEnded(original.hasEnded())
                .setStartEpochNanos(original.startEpochNanos)
                .setEndEpochNanos(original.endEpochNanos)
                .setAttributes(original.attributes)
                .setResource(resource)
                .build()
        }
}
