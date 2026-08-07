/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.glide

import io.opentelemetry.android.common.internal.imageload.ImageLoadAttributes

/**
 * Module-local aliases for the shared image-load telemetry constants defined in
 * [ImageLoadAttributes] (in `:common`). Keeping these aliases lets the Glide instrumentation code
 * use short unqualified names while the single source of truth lives in `:common`, preventing the
 * Glide and Coil span shapes from drifting apart.
 */
internal const val IMAGE_LOAD_SPAN_NAME = ImageLoadAttributes.IMAGE_LOAD_SPAN_NAME

internal val ATTR_IMAGE_URL = ImageLoadAttributes.ATTR_IMAGE_URL
internal val ATTR_IMAGE_SOURCE = ImageLoadAttributes.ATTR_IMAGE_SOURCE
internal val ATTR_IMAGE_LOAD_STATUS = ImageLoadAttributes.ATTR_IMAGE_LOAD_STATUS
internal val ATTR_IMAGE_MODEL_TYPE = ImageLoadAttributes.ATTR_IMAGE_MODEL_TYPE
internal val ATTR_IMAGE_IS_FIRST_RESOURCE = ImageLoadAttributes.ATTR_IMAGE_IS_FIRST_RESOURCE
internal val ATTR_IMAGE_TARGET_VIEW_ID = ImageLoadAttributes.ATTR_IMAGE_TARGET_VIEW_ID
internal val ATTR_IMAGE_TARGET_VIEW_TYPE = ImageLoadAttributes.ATTR_IMAGE_TARGET_VIEW_TYPE
internal val ATTR_IMAGE_ERROR_TYPE = ImageLoadAttributes.ATTR_IMAGE_ERROR_TYPE

internal const val STATUS_SUCCESS = ImageLoadAttributes.STATUS_SUCCESS
internal const val STATUS_ERROR = ImageLoadAttributes.STATUS_ERROR

internal const val SOURCE_MEMORY = ImageLoadAttributes.SOURCE_MEMORY
internal const val SOURCE_DISK = ImageLoadAttributes.SOURCE_DISK
internal const val SOURCE_NETWORK = ImageLoadAttributes.SOURCE_NETWORK
internal const val SOURCE_DISK_CACHE = ImageLoadAttributes.SOURCE_DISK_CACHE

internal const val VALUE_UNKNOWN = ImageLoadAttributes.VALUE_UNKNOWN

/**
 * Strips query parameters from a raw URL/model string to avoid leaking sensitive tokens
 * (auth, signatures) into telemetry attributes. Critical for BFSI compliance.
 */
internal fun sanitizeModel(raw: String): String = ImageLoadAttributes.sanitizeUrl(raw)
