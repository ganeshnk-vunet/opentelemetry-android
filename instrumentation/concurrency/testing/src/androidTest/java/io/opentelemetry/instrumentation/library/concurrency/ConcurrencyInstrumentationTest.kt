/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency

import android.os.Handler
import android.os.Looper
import io.opentelemetry.android.test.common.OpenTelemetryRumRule
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConcurrencyInstrumentationTest {
    private lateinit var server: MockWebServer

    @get:Rule
    internal var openTelemetryRumRule: OpenTelemetryRumRule = OpenTelemetryRumRule()

    @Before
    @Throws(Exception::class)
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        server.close()
    }

    @Test
    @Throws(Exception::class)
    fun coroutinePropagation() {
        val lock = CountDownLatch(1)
        val parentSpan = openTelemetryRumRule.getSpan()

        parentSpan.makeCurrent().use {
            runBlocking {
                withContext(Dispatchers.IO) {
                    assertThat(Span.current().spanContext.traceId)
                        .isEqualTo(parentSpan.spanContext.traceId)
                    lock.countDown()
                }
            }
        }
        assertThat(lock.await(5, TimeUnit.SECONDS)).isTrue()
        parentSpan.end()
    }

    @Test
    @Throws(Exception::class)
    fun coroutineToOkHttpParentLinkage() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val lock = CountDownLatch(1)
        val parentSpan = openTelemetryRumRule.getSpan()

        parentSpan.makeCurrent().use {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder().build()
                    client
                        .newCall(Request.Builder().url(server.url("/test/")).build())
                        .execute()
                        .close()
                    lock.countDown()
                }
            }
        }
        assertThat(lock.await(5, TimeUnit.SECONDS)).isTrue()
        parentSpan.end()

        val httpSpan =
            openTelemetryRumRule.inMemorySpanExporter.finishedSpanItems.firstOrNull { span ->
                span.attributes.get(AttributeKey.stringKey("http.request.method")) == "GET"
            }
        assertThat(httpSpan).isNotNull()
        assertThat(httpSpan!!.traceId).isEqualTo(parentSpan.spanContext.traceId)
        assertThat(httpSpan.parentSpanId).isEqualTo(parentSpan.spanContext.spanId)
    }

    @Test
    @Throws(Exception::class)
    fun handlerPropagation() {
        val lock = CountDownLatch(1)
        val parentSpan = openTelemetryRumRule.getSpan()
        val handler = Handler(Looper.getMainLooper())

        parentSpan.makeCurrent().use {
            handler.post {
                assertThat(Span.current().spanContext.traceId)
                    .isEqualTo(parentSpan.spanContext.traceId)
                lock.countDown()
            }
        }
        assertThat(lock.await(5, TimeUnit.SECONDS)).isTrue()
        parentSpan.end()
    }

    @Test
    @Throws(Exception::class)
    fun executorPropagation() {
        val lock = CountDownLatch(1)
        val parentSpan = openTelemetryRumRule.getSpan()
        val executor = Executors.newSingleThreadExecutor()

        try {
            parentSpan.makeCurrent().use {
                executor.execute {
                    assertThat(Span.current().spanContext.traceId)
                        .isEqualTo(parentSpan.spanContext.traceId)
                    lock.countDown()
                }
            }
            assertThat(lock.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            executor.shutdownNow()
            parentSpan.end()
        }
    }
}
