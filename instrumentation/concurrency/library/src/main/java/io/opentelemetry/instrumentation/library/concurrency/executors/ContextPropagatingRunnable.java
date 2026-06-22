/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.concurrency.executors;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Propagates an OpenTelemetry {@link Context} into a {@link Runnable} execution.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ContextPropagatingRunnable implements Runnable {

    private final Context context;
    private final Runnable delegate;

    private ContextPropagatingRunnable(Context context, Runnable delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    public static Runnable wrap(Runnable delegate) {
        if (delegate == null) {
            return null;
        }
        Context context = Context.current();
        if (context == Context.root() || delegate instanceof ContextPropagatingRunnable) {
            return delegate;
        }
        return new ContextPropagatingRunnable(context, delegate);
    }

    @Override
    public void run() {
        try (Scope ignored = context.makeCurrent()) {
            delegate.run();
        }
    }
}
