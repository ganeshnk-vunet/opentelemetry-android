/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.mockk
import io.opentelemetry.instrumentation.library.okhttp.OkHttpInstrumentation
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * End-to-end coverage of when an `http.client` span ends, driven through a real [OkHttpClient] and
 * a real [MockWebServer].
 *
 * Byte Buddy weaving is not active in a JVM unit test, but the woven entry point
 * [OkHttpSingletons.applyClientInstrumentation] is ordinary code and can be invoked directly, which
 * gives a client carrying the genuine network interceptor and the genuine wrapped `EventListener`.
 * That matters here: every defect these tests cover lives in the interaction between those two,
 * and none of it is reachable from the mock-based coordinator tests.
 */
class OkHttpSpanLifecycleTest {
    @JvmField
    @RegisterExtension
    val otel: OpenTelemetryExtension = OpenTelemetryExtension.create()

    private lateinit var server: MockWebServer
    private var fakeNanos: Long = 0L

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        fakeNanos = 0L
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
        OkHttpSingletons.eventListenerWiringFailed = false
    }

    @AfterEach
    fun tearDown() {
        server.close()
        OkHttpCallTimingStore.clear()
        OkHttpCallCompletionCoordinator.clear()
        OkHttpSingletons.eventListenerWiringFailed = false
    }

    private fun instrument(
        configure: OkHttpInstrumentation.() -> Unit = {},
        clientBuilder: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient {
        val instrumentation = OkHttpInstrumentation().apply(configure)
        OkHttpSingletons.configure(instrumentation, otel.openTelemetry)
        // Tests drive expiry themselves; a real scheduler would race the assertions.
        OkHttpCallCompletionCoordinator.setWatchdogScheduler(null)
        OkHttpCallCompletionCoordinator.setNanoTimeSource { fakeNanos }
        val builder = OkHttpClient.Builder().apply(clientBuilder)
        OkHttpSingletons.applyClientInstrumentation(builder)
        return builder.build()
    }

    private fun get(
        client: OkHttpClient,
        path: String = "/",
    ): Response = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `span ends when the last byte arrives, not when the caller closes the body`() {
        server.enqueue(MockResponse.Builder().body("hello").build())
        val client = instrument()

        val response = get(client)
        // Read to exhaustion but deliberately leave the body open. OkHttp reports callEnd when the
        // body reaches EOF *or* is closed, whichever comes first, so a fully consumed body ends
        // the span even if the caller never closes it.
        val body = response.body!!.source().readUtf8()

        assertThat(body).isEqualTo("hello")
        assertThat(otel.spans).hasSize(1)
        assertThat(otel.spans[0].hasEnded()).isTrue()
        assertThat(otel.spans[0].endEpochNanos - otel.spans[0].startEpochNanos).isPositive()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()

        response.close()
    }

    @Test
    fun `a body that is never read is ended by the watchdog and marked abandoned`() {
        server.enqueue(MockResponse.Builder().body("never read").build())
        val client = instrument()

        val response = get(client)
        // Nothing reads the body, so neither responseBodyEnd nor callEnd will ever arrive.
        assertThat(otel.spans).isEmpty()

        fakeNanos += TimeUnit.SECONDS.toNanos(301)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).hasSize(1)
        val span = otel.spans[0]
        assertThat(span.hasEnded()).isTrue()
        assertThat(span.attributes.asMap().mapKeys { it.key.key })
            .containsEntry(OkHttpTimingAttributes.ABANDONED, true)
            .containsEntry(OkHttpTimingAttributes.PHASES_COMPLETE, false)
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()

        response.close()
    }

    @Test
    fun `an in-flight call is left alone before its deadline`() {
        server.enqueue(MockResponse.Builder().body("still going").build())
        val client = instrument()

        val response = get(client)
        fakeNanos += TimeUnit.SECONDS.toNanos(299)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).isEmpty()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)

        response.close()
    }

    @Test
    fun `a genuinely slow call reports its real duration and is not truncated`() {
        server.enqueue(MockResponse.Builder().body("slow").build())
        val client = instrument()

        val response = get(client)
        // Two minutes is slow, not broken -- a finding this SDK exists to surface. The watchdog
        // must leave it alone so the span carries its true duration; capping it here would delete
        // exactly the signal worth having.
        fakeNanos += TimeUnit.MINUTES.toNanos(2)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).isEmpty()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)

        // It completes on its own and is reported normally, with no abandoned marker.
        response.body!!.source().readUtf8()
        val span = otel.spans.single()
        assertThat(span.attributes.asMap().mapKeys { it.key.key })
            .doesNotContainKey(OkHttpTimingAttributes.ABANDONED)

        response.close()
    }

    @Test
    fun `a slow body read gets its own budget and is not truncated`() {
        server.enqueue(MockResponse.Builder().body("large payload").build())
        val client = instrument()

        // Headers arrive immediately; the body is then read slowly, as a large download on a poor
        // connection would be. Measuring the body against a deadline set when the request started
        // -- or against a short fixed window from the headers -- would truncate a legitimate
        // transfer and mark it abandoned, which is the same defect as truncating a slow server.
        val response = get(client)
        fakeNanos += TimeUnit.MINUTES.toNanos(4)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).isEmpty()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)

        response.body!!.source().readUtf8()
        assertThat(otel.spans.single().attributes.asMap().mapKeys { it.key.key })
            .doesNotContainKey(OkHttpTimingAttributes.ABANDONED)

        response.close()
    }

    @Test
    fun `a body that never arrives is still bounded once the headers budget expires`() {
        server.enqueue(MockResponse.Builder().body("never read").build())
        val client = instrument()

        // The headers phase has its own budget too, so a call that stalls before the response
        // cannot sit pending forever -- the backstop is never removed, only re-based.
        val response = get(client)
        fakeNanos += TimeUnit.MINUTES.toNanos(6)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).hasSize(1)
        assertThat(otel.spans[0].attributes.asMap().mapKeys { it.key.key })
            .containsEntry(OkHttpTimingAttributes.ABANDONED, true)

        response.close()
    }

    @Test
    fun `the configured cap is honoured`() {
        server.enqueue(MockResponse.Builder().body("x").build())
        val client = instrument(configure = { setMaxCallDurationMillis(5_000L) })

        val response = get(client)
        fakeNanos += TimeUnit.SECONDS.toNanos(6)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).hasSize(1)

        response.close()
    }

    @Test
    fun `okhttp's own callTimeout takes precedence over the default cap`() {
        server.enqueue(MockResponse.Builder().body("x").build())
        val client = instrument(clientBuilder = { callTimeout(5, TimeUnit.SECONDS) })

        val response = get(client)
        fakeNanos += TimeUnit.SECONDS.toNanos(6)
        OkHttpCallCompletionCoordinator.sweep()

        // 6s is well inside the 5-minute default but past the client's own 5s callTimeout.
        assertThat(otel.spans).hasSize(1)

        response.close()
    }

    @Test
    fun `a redirect produces one ended span per wire attempt`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .setHeader("Location", "/final")
                .build(),
        )
        server.enqueue(MockResponse.Builder().body("done").build())
        val client = instrument()

        get(client, "/start").use { response ->
            assertThat(response.body!!.string()).isEqualTo("done")
        }

        // The tracing interceptor is a network interceptor, so it runs once per attempt. Before
        // this fix the second registration overwrote the first, stranding a started span that
        // nothing could ever end.
        assertThat(otel.spans).hasSize(2)
        assertThat(otel.spans).allMatch { it.hasEnded() }
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()

        val attrs = { span: SpanData -> span.attributes.asMap().mapKeys { it.key.key } }
        // Previous attempt is closed without consuming the per-Call timing row.
        assertThat(attrs(otel.spans[0])).containsEntry(OkHttpTimingAttributes.PHASES_COMPLETE, false)
        assertThat(attrs(otel.spans[0])).doesNotContainKey(OkHttpTimingAttributes.DNS_MS)
        // The surviving attempt still gets phase timing recorded against the Call.
        // total_ms is not asserted here: tests inject a fake nanoTime for the watchdog
        // deadline, which would make callEnd < callStart and drop total_ms.
        assertThat(attrs(otel.spans[1])).containsKey(OkHttpTimingAttributes.DNS_MS)
        assertThat(attrs(otel.spans[1])).containsKey(OkHttpTimingAttributes.TTFB_MS)
    }

    @Test
    fun `spans still end when the event listener could not be wired`() {
        server.enqueue(MockResponse.Builder().body("minified").build())
        // Must be set *before* applyClientInstrumentation: a minified build never installs the
        // listener, and a successful wrap after flipping this flag is not the failure mode.
        OkHttpSingletons.eventListenerWiringFailed = true
        val client = instrument()

        val response = get(client)

        assertThat(otel.spans).hasSize(1)
        assertThat(otel.spans[0].hasEnded()).isTrue()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isZero()

        response.close()
    }

    @Test
    fun `timing state for calls that never start a span is reclaimed`() {
        instrument()
        // A websocket upgrade reaches the EventListener but skips network interceptors, so it
        // leaves timing state behind that no span completion would ever collect.
        val orphan = mockk<Call>(relaxed = true)
        OkHttpCallTimingStore.stateFor(orphan).apply {
            createdAtNanos = fakeNanos
            callStartNanos = fakeNanos
        }

        fakeNanos += TimeUnit.SECONDS.toNanos(301)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(OkHttpCallTimingStore.remove(orphan)).isNull()
    }

    @Test
    fun `timing state for an in-flight dns lookup is not reclaimed`() {
        instrument()
        val inFlight = mockk<Call>(relaxed = true)
        OkHttpCallTimingStore.stateFor(inFlight).apply {
            createdAtNanos = fakeNanos
            callStartNanos = fakeNanos
            dnsStartNanos = fakeNanos
        }

        fakeNanos += TimeUnit.SECONDS.toNanos(301)
        OkHttpCallCompletionCoordinator.sweep()

        val timing = OkHttpCallTimingStore.remove(inFlight)
        assertThat(timing).isNotNull()
    }
}
