/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.handlers;

import io.opentelemetry.instrumentation.library.concurrency.executors.ContextPropagatingRunnable;
import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice applied to {@code Handler.post*} methods. Loaded by {@link
 * io.opentelemetry.instrumentation.agent.concurrency.handlers.HandlerPostPlugin} from
 * concurrency-agent at compile time.
 */
public class HandlerPostAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
        runnable = ContextPropagatingRunnable.wrap(runnable);
    }
}
