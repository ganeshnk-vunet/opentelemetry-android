/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.executors

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ContextPropagatingRunnableTest {
    @Test
    fun wrapReturnsDelegateWhenCurrentContextIsRoot() {
        val delegate = Runnable {}
        assertThat(ContextPropagatingRunnable.wrap(delegate)).isSameAs(delegate)
    }

    @Test
    fun wrapDoesNotDoubleWrap() {
        val span = Span.getInvalid()
        val parentContext = Context.root().with(span)

        parentContext.makeCurrent().use {
            val first = ContextPropagatingRunnable.wrap(Runnable {})
            val second = ContextPropagatingRunnable.wrap(first)
            assertThat(second).isSameAs(first)
        }
    }

    @Test
    fun runRestoresCapturedContextOnWorkerThread() {
        val span = Span.getInvalid()
        val parentContext = Context.root().with(span)
        val observed = AtomicReference<Context>()

        val wrapped =
            parentContext.makeCurrent().use {
                ContextPropagatingRunnable.wrap {
                    observed.set(Context.current())
                }
            }

        wrapped.run()
        assertThat(observed.get()).isEqualTo(parentContext)
    }
}
