/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.imageload

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span

/**
 * Shared span name, attribute keys, and canonical label values for image-load telemetry.
 *
 * Both the Glide and Coil instrumentation modules emit the same `image.load` span shape, so the
 * constants live here in `:common` to keep the two instrumentations from drifting over time.
 *
 * This type is in an `internal`-named package and is **not** part of the stable public API; it is
 * `public` at the language level only because it is referenced across module boundaries. Treat it
 * as an internal SDK detail subject to change.
 */
object ImageLoadAttributes {
    const val IMAGE_LOAD_SPAN_NAME: String = "image.load"

    @JvmField
    val ATTR_IMAGE_URL: AttributeKey<String> = AttributeKey.stringKey("image.url")

    @JvmField
    val ATTR_IMAGE_SOURCE: AttributeKey<String> = AttributeKey.stringKey("image.source")

    @JvmField
    val ATTR_IMAGE_LOAD_STATUS: AttributeKey<String> = AttributeKey.stringKey("image.load.status")

    @JvmField
    val ATTR_IMAGE_MODEL_TYPE: AttributeKey<String> = AttributeKey.stringKey("image.model_type")

    @JvmField
    val ATTR_IMAGE_IS_FIRST_RESOURCE: AttributeKey<Boolean> =
        AttributeKey.booleanKey("image.is_first_resource")

    const val STATUS_SUCCESS: String = "success"
    const val STATUS_ERROR: String = "error"
    const val STATUS_CANCELLED: String = "cancelled"

    const val SOURCE_MEMORY: String = "memory"
    const val SOURCE_DISK: String = "disk"
    const val SOURCE_NETWORK: String = "network"
    const val SOURCE_DISK_CACHE: String = "disk_cache"

    /**
     * Maximum length for URL-like attribute values. Protects the export pipeline from
     * pathological models such as base64 `data:` URIs (which have no query separator and can
     * be hundreds of kilobytes long).
     */
    private const val MAX_ATTRIBUTE_LENGTH = 512

    /** `?` or `#` followed by non-whitespace — used to scrub URLs embedded in free text. */
    private val EMBEDDED_QUERY_OR_FRAGMENT = Regex("""[?#]\S*""")

    private val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
    private val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")

    /**
     * Strips query parameters and fragments from a raw URL/model string to avoid leaking
     * sensitive tokens (auth, signatures) into telemetry attributes, and truncates the result
     * to [MAX_ATTRIBUTE_LENGTH]. Critical for BFSI compliance.
     *
     * Fails closed: an input that is blank up to the first `?`/`#` (e.g. `"?token=..."`)
     * yields an empty string — never the raw input.
     */
    fun sanitizeUrl(raw: String): String =
        raw
            .substringBefore('?')
            .substringBefore('#')
            .take(MAX_ATTRIBUTE_LENGTH)

    /**
     * Scrubs URL query strings / fragments embedded anywhere in a free-text error message and
     * truncates to [MAX_ATTRIBUTE_LENGTH]. Exception messages from HTTP stacks routinely embed
     * the full request URL (tokens included), so they must never be recorded verbatim.
     */
    fun sanitizeErrorMessage(raw: String?): String? =
        raw
            ?.replace(EMBEDDED_QUERY_OR_FRAGMENT, "")
            ?.take(MAX_ATTRIBUTE_LENGTH)

    /**
     * Records a sanitised `exception` event on [span]: the exception class name plus a message
     * scrubbed via [sanitizeErrorMessage]. Deliberately does **not** use
     * [Span.recordException] — that would serialise raw messages of the whole cause chain
     * (and stack-trace text), which for HTTP failures typically contains the unsanitised URL.
     */
    fun recordSanitizedException(
        span: Span,
        throwable: Throwable,
    ) {
        val attributes = Attributes.builder().put(EXCEPTION_TYPE, throwable.javaClass.name)
        sanitizeErrorMessage(throwable.message)
            ?.takeIf { it.isNotBlank() }
            ?.let { attributes.put(EXCEPTION_MESSAGE, it) }
        span.addEvent("exception", attributes.build())
    }
}
