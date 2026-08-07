/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.export

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.trace.TestSpanData
import io.opentelemetry.sdk.trace.data.StatusData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ActionSummarizerTest {
    @Test
    fun clickButton() {
        val span =
            TestSpanHelper.span(
                RumConstants.UI_INTERACTION_SPAN_NAME,
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "button",
                    AttributeKey.stringKey("app.widget.name"), "Pay",
                    RumConstants.SCREEN_NAME_KEY, "PaymentScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Clicked button 'Pay' on PaymentScreen")
    }

    @Test
    fun clickToggleOn() {
        val span =
            TestSpanHelper.span(
                RumConstants.UI_INTERACTION_SPAN_NAME,
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "switch",
                    AttributeKey.stringKey("app.widget.name"), "Dark Mode",
                    AttributeKey.booleanKey("app.widget.checked"), true,
                    RumConstants.SCREEN_NAME_KEY, "SettingsScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Toggled switch 'Dark Mode' ON on SettingsScreen")
    }

    @Test
    fun clickToggleOff() {
        val span =
            TestSpanHelper.span(
                RumConstants.UI_INTERACTION_SPAN_NAME,
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "checkbox",
                    AttributeKey.stringKey("app.widget.name"), "Notifications",
                    AttributeKey.booleanKey("app.widget.checked"), false,
                    RumConstants.SCREEN_NAME_KEY, "SettingsScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Toggled checkbox 'Notifications' OFF on SettingsScreen")
    }

    @Test
    fun clickWithoutScreenName() {
        val span =
            TestSpanHelper.span(
                RumConstants.UI_INTERACTION_SPAN_NAME,
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "button",
                    AttributeKey.stringKey("app.widget.name"), "Submit",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Clicked button 'Submit'")
    }

    @Test
    fun clickWithoutWidgetName() {
        val span =
            TestSpanHelper.span(
                RumConstants.UI_INTERACTION_SPAN_NAME,
                Attributes.of(
                    AttributeKey.stringKey("app.widget.type"), "image",
                    RumConstants.SCREEN_NAME_KEY, "HomeScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Clicked image on HomeScreen")
    }

    @Test
    fun appStartCold() {
        val span =
            TestSpanHelper.span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(
                    RumConstants.START_TYPE_KEY, "cold",
                    AttributeKey.stringKey("activity.name"), "MainActivity",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("App cold start (MainActivity)")
    }

    @Test
    fun appStartWarm() {
        val span =
            TestSpanHelper.span(
                RumConstants.APP_START_SPAN_NAME,
                Attributes.of(
                    RumConstants.START_TYPE_KEY, "warm",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("App warm start")
    }

    @Test
    fun activityLifecycle() {
        val span =
            TestSpanHelper.span(
                RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME,
                Attributes.of(
                    RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY, "Resumed",
                    AttributeKey.stringKey("activity.name"), "MainActivity",
                    RumConstants.SCREEN_NAME_KEY, "HomeScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Activity Resumed: MainActivity on HomeScreen")
    }

    @Test
    fun activityLifecycleWithoutScreen() {
        val span =
            TestSpanHelper.span(
                RumConstants.ACTIVITY_LIFECYCLE_SPAN_NAME,
                Attributes.of(
                    RumConstants.ACTIVITY_LIFECYCLE_EVENT_KEY, "Created",
                    AttributeKey.stringKey("activity.name"), "LoginActivity",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Activity Created: LoginActivity")
    }

    @Test
    fun fragmentLifecycle() {
        val span =
            TestSpanHelper.span(
                RumConstants.FRAGMENT_LIFECYCLE_SPAN_NAME,
                Attributes.of(
                    RumConstants.FRAGMENT_LIFECYCLE_EVENT_KEY, "Created",
                    AttributeKey.stringKey("fragment.name"), "CartFragment",
                    RumConstants.SCREEN_NAME_KEY, "CartScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Fragment Created: CartFragment on CartScreen")
    }

    @Test
    fun navigationWithSource() {
        val span =
            TestSpanHelper.span(
                "ui.navigation",
                Attributes.of(
                    AttributeKey.stringKey("navigation.source.name"), "HomeScreen",
                    AttributeKey.stringKey("navigation.destination.name"), "DetailsScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Navigated from HomeScreen to DetailsScreen")
    }

    @Test
    fun navigationWithoutSource() {
        val span =
            TestSpanHelper.span(
                "ui.navigation",
                Attributes.of(
                    AttributeKey.stringKey("navigation.destination.name"), "DetailsScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Navigated to DetailsScreen")
    }

    @Test
    fun imageLoadSuccess() {
        val span =
            TestSpanHelper.span(
                "image.load",
                Attributes.of(
                    AttributeKey.stringKey("image.source"), "network",
                    AttributeKey.stringKey("image.load.status"), "success",
                    RumConstants.SCREEN_NAME_KEY, "ProductScreen",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Image loaded from network (success) on ProductScreen")
    }

    @Test
    fun crashWithMessage() {
        val span =
            TestSpanHelper.span(
                "device.crash",
                Attributes.of(
                    AttributeKey.stringKey("exception.type"), "NullPointerException",
                    AttributeKey.stringKey("exception.message"), "Attempt to invoke virtual method on a null object reference",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Crash: NullPointerException - Attempt to invoke virtual method on a null object reference")
    }

    @Test
    fun crashWithLongMessageTruncates() {
        val longMessage = "A".repeat(100)
        val span =
            TestSpanHelper.span(
                "device.crash",
                Attributes.of(
                    AttributeKey.stringKey("exception.type"), "RuntimeException",
                    AttributeKey.stringKey("exception.message"), longMessage,
                ),
            )
        val summary = ActionSummarizer.summarize(span)!!
        assertThat(summary).isEqualTo("Crash: RuntimeException - ${"A".repeat(80)}...")
    }

    @Test
    fun crashWithoutMessage() {
        val span =
            TestSpanHelper.span(
                "device.crash",
                Attributes.of(
                    AttributeKey.stringKey("exception.type"), "OutOfMemoryError",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("Crash: OutOfMemoryError")
    }

    @Test
    fun anr() {
        val span =
            TestSpanHelper.span(
                "device.anr",
                Attributes.of(
                    AttributeKey.stringKey("thread.name"), "main",
                ),
            )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("ANR detected on thread 'main'")
    }

    @Test
    fun unsupportedSpanReturnsNull() {
        val span = TestSpanHelper.span("app.metrics", Attributes.empty())
        assertThat(ActionSummarizer.summarize(span)).isNull()
    }

    @Test
    fun slowRendersReturnsNull() {
        val span = TestSpanHelper.span("slowRenders", Attributes.empty())
        assertThat(ActionSummarizer.summarize(span)).isNull()
    }

    @Test
    fun unknownSpanReturnsNull() {
        val span = TestSpanHelper.span("some.random.span", Attributes.empty())
        assertThat(ActionSummarizer.summarize(span)).isNull()
    }

    @Test
    fun httpGetWithFullUrl() {
        val span = clientSpan(
            "GET",
            Attributes.of(
                AttributeKey.stringKey("http.request.method"), "GET",
                AttributeKey.stringKey("url.full"), "https://api.example.com/users",
            ),
        )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("GET - https://api.example.com/users")
    }

    @Test
    fun httpPostWithFullUrl() {
        val span = clientSpan(
            "POST",
            Attributes.of(
                AttributeKey.stringKey("http.request.method"), "POST",
                AttributeKey.stringKey("url.full"), "https://api.example.com/orders",
            ),
        )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("POST - https://api.example.com/orders")
    }

    @Test
    fun httpWithServerAddressFallback() {
        val span = clientSpan(
            "GET",
            Attributes.of(
                AttributeKey.stringKey("http.request.method"), "GET",
                AttributeKey.stringKey("server.address"), "api.example.com",
            ),
        )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("GET - api.example.com")
    }

    @Test
    fun httpWithMethodOnly() {
        val span = clientSpan(
            "GET",
            Attributes.of(
                AttributeKey.stringKey("http.request.method"), "GET",
            ),
        )
        assertThat(ActionSummarizer.summarize(span))
            .isEqualTo("GET")
    }

    @Test
    fun nonHttpClientSpanReturnsNull() {
        val span = TestSpanHelper.span("some.random.span", Attributes.empty())
        assertThat(ActionSummarizer.summarize(span)).isNull()
    }

    private fun clientSpan(name: String, attributes: Attributes) =
        TestSpanData.builder()
            .setName(name)
            .setKind(SpanKind.CLIENT)
            .setStatus(StatusData.unset())
            .setHasEnded(true)
            .setStartEpochNanos(0)
            .setEndEpochNanos(123)
            .setAttributes(attributes)
            .build()
}
