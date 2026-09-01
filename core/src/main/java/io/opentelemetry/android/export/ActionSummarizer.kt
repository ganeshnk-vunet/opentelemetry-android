/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.data.SpanData

internal object ActionSummarizer {
    private val WIDGET_TYPE = AttributeKey.stringKey("app.widget.type")
    private val WIDGET_NAME = AttributeKey.stringKey("app.widget.name")
    private val WIDGET_CHECKED = AttributeKey.booleanKey("ui.control.value.checked")
    private val ACTIVITY_NAME = AttributeKey.stringKey("activity.name")
    private val FRAGMENT_NAME = AttributeKey.stringKey("fragment.name")
    private val NAV_SOURCE = AttributeKey.stringKey("navigation.source.name")
    private val NAV_DEST = AttributeKey.stringKey("navigation.destination.name")
    private val IMAGE_SOURCE = AttributeKey.stringKey("image.source")
    private val IMAGE_STATUS = AttributeKey.stringKey("image.load.status")
    private val EXCEPTION_TYPE = AttributeKey.stringKey("exception.type")
    private val EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message")
    private val THREAD_NAME = AttributeKey.stringKey("thread.name")
    private val HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method")
    private val URL_FULL = AttributeKey.stringKey("url.full")
    private val SERVER_ADDRESS = AttributeKey.stringKey("server.address")

    private const val MAX_CRASH_MESSAGE_LENGTH = 80

    private val TOGGLE_TYPES = setOf("switch", "checkbox", "radio", "toggle")

    fun summarize(span: SpanData): String? =
        when (span.name) {
            RumConstants.UI_INTERACTION_SPAN_NAME -> summarizeClick(span)
            RumConstants.APP_START_SPAN_NAME -> summarizeAppStart(span)
            RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME -> summarizeActivityLifecycle(span)
            RumConstants.FRAGMENT_LIFECYCLE_SPAN_NAME -> summarizeFragmentLifecycle(span)
            "ui.navigation" -> summarizeNavigation(span)
            "image.load" -> summarizeImageLoad(span)
            "device.crash" -> summarizeCrash(span)
            "device.anr" -> summarizeAnr(span)
            else -> summarizeHttpIfApplicable(span)
        }

    private fun summarizeClick(span: SpanData): String {
        val attrs = span.attributes
        val widgetType = attrs.get(WIDGET_TYPE) ?: "element"
        val widgetName = attrs.get(WIDGET_NAME)
        val checked = attrs.get(WIDGET_CHECKED)
        val screen = attrs.get(RumConstants.SCREEN_NAME_KEY)

        val isToggle = widgetType in TOGGLE_TYPES && checked != null

        val action =
            if (isToggle) {
                val state = if (checked == true) "ON" else "OFF"
                val namepart = if (widgetName != null) " '$widgetName'" else ""
                "Toggled $widgetType$namepart $state"
            } else {
                val namepart = if (widgetName != null) " '$widgetName'" else ""
                "Clicked $widgetType$namepart"
            }

        return if (screen != null) "$action on $screen" else action
    }

    private fun summarizeAppStart(span: SpanData): String {
        val attrs = span.attributes
        val startType = attrs.get(RumConstants.START_TYPE_KEY) ?: "unknown"
        val activity = attrs.get(ACTIVITY_NAME)
        return if (activity != null) {
            "App $startType start ($activity)"
        } else {
            "App $startType start"
        }
    }

    private fun summarizeActivityLifecycle(span: SpanData): String {
        val attrs = span.attributes
        val event = attrs.get(RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY) ?: "unknown"
        val activity = attrs.get(ACTIVITY_NAME) ?: "unknown"
        val screen = attrs.get(RumConstants.SCREEN_NAME_KEY)
        val base = "Activity $event: $activity"
        return if (screen != null) "$base on $screen" else base
    }

    private fun summarizeFragmentLifecycle(span: SpanData): String {
        val attrs = span.attributes
        val event = attrs.get(RumConstants.FRAGMENT_LIFECYCLE_EVENT_KEY) ?: "unknown"
        val fragment = attrs.get(FRAGMENT_NAME) ?: "unknown"
        val screen = attrs.get(RumConstants.SCREEN_NAME_KEY)
        val base = "Fragment $event: $fragment"
        return if (screen != null) "$base on $screen" else base
    }

    private fun summarizeNavigation(span: SpanData): String {
        val attrs = span.attributes
        val source = attrs.get(NAV_SOURCE)
        val dest = attrs.get(NAV_DEST) ?: "unknown"
        return if (source != null) {
            "Navigated from $source to $dest"
        } else {
            "Navigated to $dest"
        }
    }

    private fun summarizeImageLoad(span: SpanData): String {
        val attrs = span.attributes
        val source = attrs.get(IMAGE_SOURCE) ?: "unknown"
        val status = attrs.get(IMAGE_STATUS) ?: "unknown"
        val screen = attrs.get(RumConstants.SCREEN_NAME_KEY)
        val base = "Image loaded from $source ($status)"
        return if (screen != null) "$base on $screen" else base
    }

    private fun summarizeCrash(span: SpanData): String {
        val attrs = span.attributes
        val exType = attrs.get(EXCEPTION_TYPE) ?: "unknown"
        val exMessage = attrs.get(EXCEPTION_MESSAGE)
        return if (exMessage != null) {
            val truncated =
                if (exMessage.length > MAX_CRASH_MESSAGE_LENGTH) {
                    exMessage.take(MAX_CRASH_MESSAGE_LENGTH) + "..."
                } else {
                    exMessage
                }
            "Crash: $exType - $truncated"
        } else {
            "Crash: $exType"
        }
    }

    private fun summarizeAnr(span: SpanData): String {
        val threadName = span.attributes.get(THREAD_NAME) ?: "main"
        return "ANR detected on thread '$threadName'"
    }

    private fun summarizeHttpIfApplicable(span: SpanData): String? {
        if (span.kind != SpanKind.CLIENT) return null
        val method = span.attributes.get(HTTP_REQUEST_METHOD) ?: return null
        val url = span.attributes.get(URL_FULL) ?: span.attributes.get(SERVER_ADDRESS)
        return if (url != null) "$method - $url" else method
    }
}
