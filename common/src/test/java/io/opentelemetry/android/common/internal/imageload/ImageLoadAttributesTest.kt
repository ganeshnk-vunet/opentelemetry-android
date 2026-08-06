/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.imageload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLoadAttributesTest {
    @Test
    fun sanitizeUrl_stripsQueryString() {
        assertEquals(
            "https://cdn.bank.com/photo.jpg",
            ImageLoadAttributes.sanitizeUrl("https://cdn.bank.com/photo.jpg?token=SECRET&sig=abc"),
        )
    }

    @Test
    fun sanitizeUrl_stripsFragment() {
        assertEquals(
            "https://cdn.bank.com/photo.jpg",
            ImageLoadAttributes.sanitizeUrl("https://cdn.bank.com/photo.jpg#access_token=SECRET"),
        )
    }

    @Test
    fun sanitizeUrl_passesCleanUrlThrough() {
        assertEquals(
            "https://cdn.bank.com/photo.jpg",
            ImageLoadAttributes.sanitizeUrl("https://cdn.bank.com/photo.jpg"),
        )
    }

    @Test
    fun sanitizeUrl_failsClosedOnBlankPrefix() {
        // A pathological input that is nothing but a query string must NOT fall back to the
        // raw value — that would leak the token this function exists to strip.
        assertEquals("", ImageLoadAttributes.sanitizeUrl("?token=SECRET"))
        assertEquals("", ImageLoadAttributes.sanitizeUrl("#access_token=SECRET"))
    }

    @Test
    fun sanitizeUrl_truncatesOversizedValues() {
        // base64 data: URIs have no query separator and can be hundreds of KB.
        val dataUri = "data:image/png;base64," + "A".repeat(100_000)
        val sanitized = ImageLoadAttributes.sanitizeUrl(dataUri)
        assertTrue(sanitized.length <= 512)
    }

    @Test
    fun sanitizeErrorMessage_scrubsEmbeddedQueryStrings() {
        val message = "Failed to fetch https://cdn.bank.com/img.jpg?token=SECRET&sig=abc after 3 retries"
        val sanitized = ImageLoadAttributes.sanitizeErrorMessage(message)!!
        assertFalse(sanitized.contains("SECRET"))
        assertFalse(sanitized.contains("token="))
        assertTrue(sanitized.contains("https://cdn.bank.com/img.jpg"))
        assertTrue(sanitized.contains("after 3 retries"))
    }

    @Test
    fun sanitizeErrorMessage_isNullSafeAndTruncates() {
        assertNull(ImageLoadAttributes.sanitizeErrorMessage(null))
        val long = "x".repeat(100_000)
        assertTrue(ImageLoadAttributes.sanitizeErrorMessage(long)!!.length <= 512)
    }
}
