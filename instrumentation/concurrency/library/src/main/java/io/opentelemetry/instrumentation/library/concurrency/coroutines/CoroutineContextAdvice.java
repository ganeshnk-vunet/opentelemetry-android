/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.coroutines;

import kotlin.coroutines.CoroutineContext;
import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice applied to {@code CoroutineContextKt.newCoroutineContext}. Loaded by {@link
 * io.opentelemetry.instrumentation.agent.concurrency.coroutines.CoroutineContextPlugin} from
 * concurrency-agent at compile time; this class must live in concurrency-library so woven
 * coroutines bytecode can resolve it at runtime.
 */
public class CoroutineContextAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Return(readOnly = false) CoroutineContext result) {
        result = KotlinCoroutinesContextHelper.addOpenTelemetryContext(result);
    }
}
