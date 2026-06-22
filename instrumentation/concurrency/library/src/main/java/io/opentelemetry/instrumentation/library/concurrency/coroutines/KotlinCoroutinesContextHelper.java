/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.coroutines;

import io.opentelemetry.context.Context;
import io.opentelemetry.extension.kotlin.ContextExtensionsKt;
import kotlin.coroutines.CoroutineContext;

/**
 * Bridges OpenTelemetry {@link Context} into Kotlin coroutine {@link CoroutineContext}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class KotlinCoroutinesContextHelper {

    private KotlinCoroutinesContextHelper() {}

    /**
     * Adds the current OpenTelemetry context to the coroutine context when it is not already
     * present.
     */
    public static CoroutineContext addOpenTelemetryContext(CoroutineContext coroutineContext) {
        Context current = Context.current();
        Context inCoroutine = ContextExtensionsKt.getOpenTelemetryContext(coroutineContext);
        if (current == inCoroutine || inCoroutine != Context.root()) {
            return coroutineContext;
        }
        return coroutineContext.plus(ContextExtensionsKt.asContextElement(current));
    }
}
