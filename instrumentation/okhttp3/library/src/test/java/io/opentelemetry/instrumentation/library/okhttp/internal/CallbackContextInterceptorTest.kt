/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.internal

import io.opentelemetry.android.common.interaction.ActiveInteractionHolder
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal class CallbackContextInterceptorTest {
    private val key: ContextKey<String> = ContextKey.named("interaction")

    @AfterEach
    fun resetActiveInteraction() {
        ActiveInteractionHolder.set(Context.root(), 0)
        ActiveInteractionHolder.current()
    }

    @Test
    fun adoptsActiveInteraction_whenNoCurrentContext() {
        val interaction = Context.root().with(key, "click")
        ActiveInteractionHolder.set(interaction, 5_000)

        var observed: Context? = null
        val chain = FakeChain { observed = Context.current() }

        OkHttpSingletons.CALLBACK_CONTEXT_INTERCEPTOR.intercept(chain)

        assertThat(observed?.get(key)).isEqualTo("click")
    }

    @Test
    fun doesNotOverrideExistingCurrentContext() {
        val interaction = Context.root().with(key, "click")
        ActiveInteractionHolder.set(interaction, 5_000)

        val existing = Context.root().with(key, "already-current")
        var observed: Context? = null
        val chain = FakeChain { observed = Context.current() }

        existing.makeCurrent().use {
            OkHttpSingletons.CALLBACK_CONTEXT_INTERCEPTOR.intercept(chain)
        }

        // The fallback only applies when Context.current() is root, so the existing context wins.
        assertThat(observed?.get(key)).isEqualTo("already-current")
    }

    private class FakeChain(
        private val onProceed: () -> Unit,
    ) : Interceptor.Chain {
        private val request: Request = Request.Builder().url("https://example.com/").build()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            onProceed()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException()

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(
            timeout: Int,
            unit: java.util.concurrent.TimeUnit,
        ): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(
            timeout: Int,
            unit: java.util.concurrent.TimeUnit,
        ): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(
            timeout: Int,
            unit: java.util.concurrent.TimeUnit,
        ): Interceptor.Chain = this
    }
}
