/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.imageload

import android.content.res.Resources
import android.view.View
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Direct tests for the helpers shared by the Glide and Coil instrumentations. Both modules cover
 * these indirectly through their listeners, but the edge cases below — a blank resource entry
 * name, a null [Resources], an anonymous throwable class — are awkward to reach from there and are
 * exactly where a regression would silently produce unstable or misleading attribute values.
 */
class ImageLoadAttributesTest {
    @Test
    fun `resolveViewId returns the resource entry name when the view has one`() {
        assertThat(ImageLoadAttributes.resolveViewId(view(id = VIEW_ID, entryName = "avatar_image")))
            .isEqualTo("avatar_image")
    }

    @Test
    fun `resolveViewId buckets a view with no android id`() {
        assertThat(ImageLoadAttributes.resolveViewId(view(id = View.NO_ID)))
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNSET)
    }

    @Test
    fun `resolveViewId buckets an id with no resource-table entry`() {
        // View.generateViewId() values: getResourceEntryName throws for them.
        val view =
            view(id = GENERATED_VIEW_ID) { resources ->
                every { resources.getResourceEntryName(GENERATED_VIEW_ID) } throws
                    Resources.NotFoundException("no entry")
            }

        assertThat(ImageLoadAttributes.resolveViewId(view))
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNRESOLVED)
    }

    @Test
    fun `resolveViewId buckets a blank resource entry name rather than emitting the raw id`() {
        val view = view(id = VIEW_ID, entryName = "   ")

        assertThat(ImageLoadAttributes.resolveViewId(view))
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNRESOLVED)
    }

    @Test
    fun `resolveViewId buckets a view with no Resources`() {
        val view = mockk<View>(relaxed = true)
        every { view.id } returns VIEW_ID
        every { view.resources } returns null

        assertThat(ImageLoadAttributes.resolveViewId(view))
            .isEqualTo(ImageLoadAttributes.VIEW_ID_UNRESOLVED)
    }

    @Test
    fun `resolveViewId never emits a raw integer id`() {
        // Every fallback path must land on a fixed label: raw ids from View.generateViewId() come
        // from a process-local counter and mean something different on each launch.
        val unresolvable =
            listOf(
                view(id = View.NO_ID),
                view(id = GENERATED_VIEW_ID) { resources ->
                    every { resources.getResourceEntryName(GENERATED_VIEW_ID) } throws
                        Resources.NotFoundException("no entry")
                },
                view(id = VIEW_ID, entryName = ""),
            )

        assertThat(unresolvable.map { ImageLoadAttributes.resolveViewId(it) })
            .containsExactly(
                ImageLoadAttributes.VIEW_ID_UNSET,
                ImageLoadAttributes.VIEW_ID_UNRESOLVED,
                ImageLoadAttributes.VIEW_ID_UNRESOLVED,
            )
            .doesNotContain(GENERATED_VIEW_ID.toString(), VIEW_ID.toString())
    }

    @Test
    fun `errorType uses the fully qualified class name`() {
        // Matches semconv and the HTTP instrumentations, so image failures join the same
        // error.type breakdowns.
        assertThat(ImageLoadAttributes.errorType(java.net.SocketTimeoutException("timeout")))
            .isEqualTo("java.net.SocketTimeoutException")
    }

    @Test
    fun `errorType yields a usable name for an anonymous throwable class`() {
        val anonymous = object : RuntimeException("boom") {}
        check(anonymous.javaClass.simpleName.isBlank()) { "expected an anonymous class" }

        assertThat(ImageLoadAttributes.errorType(anonymous))
            .isNotBlank()
            .isEqualTo(anonymous.javaClass.name)
    }

    @Test
    fun `sanitizeUrl strips query parameters`() {
        assertThat(ImageLoadAttributes.sanitizeUrl("https://cdn.bank.com/a.png?token=SECRET"))
            .isEqualTo("https://cdn.bank.com/a.png")
    }

    @Test
    fun `sanitizeUrl keeps the raw value when stripping would leave nothing`() {
        assertThat(ImageLoadAttributes.sanitizeUrl("?only=query")).isEqualTo("?only=query")
    }

    private fun view(
        id: Int,
        entryName: String? = null,
        stubResources: (Resources) -> Unit = {},
    ): View {
        val resources = mockk<Resources>(relaxed = true)
        entryName?.let { every { resources.getResourceEntryName(id) } returns it }
        stubResources(resources)

        val view = mockk<View>(relaxed = true)
        every { view.id } returns id
        every { view.resources } returns resources
        return view
    }

    private companion object {
        private const val VIEW_ID = 0x7f0a0042

        /** In the range View.generateViewId() allocates from (1..0x00FFFFFF), never a resource id. */
        private const val GENERATED_VIEW_ID = 17
    }
}
