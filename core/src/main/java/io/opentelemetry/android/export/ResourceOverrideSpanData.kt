/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.SpanData

internal class ResourceOverrideSpanData(
    original: SpanData,
    private val resourceOverride: Resource,
) : DelegatingSpanData(original) {
    override fun getResource(): Resource = resourceOverride
}
