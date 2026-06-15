/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp.callback;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpCallbackAdviceHelper;
import io.opentelemetry.instrumentation.library.okhttp.internal.TracingCallback;
import net.bytebuddy.asm.Advice;
import okhttp3.Call;
import okhttp3.Callback;

/**
 * Byte Buddy advice applied to {@code RealCall.enqueue}. Loaded by {@link
 * io.opentelemetry.instrumentation.agent.okhttp.callback.OkHttpCallbackPlugin} from okhttp3-agent
 * at compile time; this class must live in okhttp3-library so woven OkHttp bytecode can resolve it
 * at runtime.
 */
public class OkHttpCallbackAdvice {

    @Advice.OnMethodEnter
    public static void enter(
            @Advice.This Call call,
            @Advice.Argument(value = 0, readOnly = false) Callback callback) {
        if (OkHttpCallbackAdviceHelper.propagateContext(call)) {
            callback = new TracingCallback(callback, Context.current());
        }
    }
}
