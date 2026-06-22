/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.executors;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice applied to {@code ThreadPoolExecutor.execute}. Loaded by {@link
 * io.opentelemetry.instrumentation.agent.concurrency.executors.ThreadPoolExecutorPlugin} from
 * concurrency-agent at compile time.
 */
public class ExecutorExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
        runnable = ContextPropagatingRunnable.wrap(runnable);
    }
}
