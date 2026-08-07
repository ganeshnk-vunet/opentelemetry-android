/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.internal.imageload

import android.view.View
import io.opentelemetry.api.common.AttributeKey

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

    /**
     * Resource entry name of the [View] the image is being loaded into (e.g. `avatar_image`), or
     * [VIEW_ID_UNSET] when the view has no id. Answers "which widget on the screen failed?" —
     * `screen.name` alone cannot distinguish two images on the same screen.
     */
    @JvmField
    val ATTR_IMAGE_TARGET_VIEW_ID: AttributeKey<String> =
        AttributeKey.stringKey("image.target.view_id")

    /** Fully-qualified class name of the target [View] (e.g. `android.widget.ImageView`). */
    @JvmField
    val ATTR_IMAGE_TARGET_VIEW_TYPE: AttributeKey<String> =
        AttributeKey.stringKey("image.target.view_type")

    /**
     * Class name of the failure cause, as a queryable attribute. The full throwable is also
     * recorded as a span *event* via `recordException`, but events cannot be grouped or charted
     * in most back-ends, so failure breakdowns need this attribute.
     */
    @JvmField
    val ATTR_IMAGE_ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("image.error.type")

    const val STATUS_SUCCESS: String = "success"
    const val STATUS_ERROR: String = "error"
    const val STATUS_CANCELLED: String = "cancelled"

    const val SOURCE_MEMORY: String = "memory"
    const val SOURCE_DISK: String = "disk"
    const val SOURCE_NETWORK: String = "network"
    const val SOURCE_DISK_CACHE: String = "disk_cache"

    /** Placeholder used when the image model or its type cannot be determined. */
    const val VALUE_UNKNOWN: String = "unknown"

    /** Value of [ATTR_IMAGE_TARGET_VIEW_ID] for a view declared without an `android:id`. */
    const val VIEW_ID_UNSET: String = "no-id"

    /**
     * Value of [ATTR_IMAGE_TARGET_VIEW_ID] for a view whose id has no entry in the resource table —
     * overwhelmingly a runtime id from [View.generateViewId] (ConstraintLayout helpers, ViewPager2
     * fragment containers, dynamically added views).
     *
     * Such ids come from a process-local counter that restarts every launch, so the raw integer is
     * neither stable for one widget across sessions nor unique between widgets. Bucketing them
     * under one label is strictly more useful for grouping than emitting a number that silently
     * means something different on each run.
     */
    const val VIEW_ID_UNRESOLVED: String = "unresolved"

    /**
     * Strips query parameters from a raw URL/model string to avoid leaking sensitive tokens
     * (auth, signatures) into telemetry attributes. Critical for BFSI compliance.
     */
    fun sanitizeUrl(raw: String): String {
        val withoutQuery = raw.substringBefore('?')
        return withoutQuery.ifBlank { raw }
    }

    /**
     * Resolves a stable, groupable identifier for [view]: the resource entry name (`avatar_image`)
     * when the view has one, otherwise one of two fixed buckets — [VIEW_ID_UNSET] when there is no
     * `android:id` at all, [VIEW_ID_UNRESOLVED] when the id exists but is absent from the resource
     * table.
     *
     * Every returned value is therefore stable across process launches. A raw integer id is
     * deliberately never emitted: for XML ids it is redundant with the entry name, and for runtime
     * ids it is actively misleading (see [VIEW_ID_UNRESOLVED]).
     *
     * This is *close to*, but deliberately stricter than, the convention used by the view-click and
     * hybrid-click instrumentations, which fall back to `view.id.toString()` and so report `-1` for
     * an id-less view and an unstable integer for a generated one. Values for those two cases will
     * not join across `image.load` and `ui.interaction` spans until those instrumentations adopt
     * this helper.
     */
    @JvmStatic
    fun resolveViewId(view: View): String {
        val id = view.id
        if (id == View.NO_ID) {
            return VIEW_ID_UNSET
        }
        return try {
            view.resources?.getResourceEntryName(id)?.ifBlank { null } ?: VIEW_ID_UNRESOLVED
        } catch (_: Throwable) {
            // Resources.NotFoundException: the id has no resource-table entry, so it came from
            // View.generateViewId() (or the Resources instance cannot see the declaring package).
            VIEW_ID_UNRESOLVED
        }
    }

    /**
     * Maps [throwable] to the value recorded in [ATTR_IMAGE_ERROR_TYPE]. The simple name keeps
     * attribute cardinality low; anonymous classes have a blank simple name and fall back to the
     * fully-qualified one.
     */
    @JvmStatic
    fun errorType(throwable: Throwable): String =
        throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
}
