/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.mockk.mockk
import io.opentelemetry.instrumentation.library.okhttp.OkHttpInstrumentation
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
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
        // Read to exhaustion but deliberately leave the body open. OkHttp reports callEnd only
        // once a body is closed, so before this fix the span stayed open for as long as the caller
        // held it -- the 23-hour durations seen in production.
        val body = response.body!!.source().readUtf8()

        assertThat(body).isEqualTo("hello")
        assertThat(otel.spans).hasSize(1)
        assertThat(otel.spans[0].hasEnded()).isTrue()
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

        fakeNanos += TimeUnit.SECONDS.toNanos(61)
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
        fakeNanos += TimeUnit.SECONDS.toNanos(59)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(otel.spans).isEmpty()
        assertThat(OkHttpCallCompletionCoordinator.pendingCount).isEqualTo(1)

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

        // 6s is well inside the 60s default but past the client's own 5s callTimeout.
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
    }

    @Test
    fun `spans still end when the event listener could not be wired`() {
        server.enqueue(MockResponse.Builder().body("minified").build())
        val client = instrument()
        // Simulates a minified build in which R8 renamed OkHttpClient.Builder.eventListenerFactory,
        // so the reflective wiring failed and no timing listener exists to complete anything.
        OkHttpSingletons.eventListenerWiringFailed = true

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

        fakeNanos += TimeUnit.SECONDS.toNanos(61)
        OkHttpCallCompletionCoordinator.sweep()

        assertThat(OkHttpCallTimingStore.remove(orphan)).isNull()
    }
}
