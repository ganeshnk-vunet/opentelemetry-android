/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.coroutines

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class KotlinCoroutinesContextHelperTest {
    @Test
    fun doesNotInjectWhenCurrentContextIsRoot() {
        val result =
            KotlinCoroutinesContextHelper.addOpenTelemetryContext(EmptyCoroutineContext)
        assertThat(result).isSameAs(EmptyCoroutineContext)
    }

    @Test
    fun injectsCurrentContextWhenCoroutineContextHasNoOtelElement() {
        val span = Span.getInvalid()
        val parentContext = Context.root().with(span)

        parentContext.makeCurrent().use {
            val result =
                KotlinCoroutinesContextHelper.addOpenTelemetryContext(EmptyCoroutineContext)
            assertThat(result.getOpenTelemetryContext()).isEqualTo(parentContext)
        }
    }

    @Test
    fun doesNotOverwriteExistingOtelContextElement() {
        val outerSpan = Span.getInvalid()
        val innerSpan = Span.getInvalid()
        val outerContext = Context.root().with(outerSpan)
        val innerContext = Context.root().with(innerSpan)
        val coroutineContext = innerContext.asContextElement()

        outerContext.makeCurrent().use {
            val result = KotlinCoroutinesContextHelper.addOpenTelemetryContext(coroutineContext)
            assertThat(result).isSameAs(coroutineContext)
            assertThat(result.getOpenTelemetryContext()).isEqualTo(innerContext)
        }
    }

    @Test
    fun injectedContextRestoresOnCoroutineResume() {
        val span = Span.getInvalid()
        val parentContext = Context.root().with(span)

        parentContext.makeCurrent().use {
            val enriched =
                KotlinCoroutinesContextHelper.addOpenTelemetryContext(EmptyCoroutineContext)
            runBlocking(enriched) {
                assertThat(Context.current()).isEqualTo(parentContext)
            }
        }
    }
}
