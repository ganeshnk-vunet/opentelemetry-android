/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.library.okhttp;

import io.opentelemetry.instrumentation.library.okhttp.internal.OkHttpSingletons;
import net.bytebuddy.asm.Advice;
import okhttp3.OkHttpClient;

/**
 * Byte Buddy advice applied to {@link OkHttpClient} construction. Loaded by {@link
 * io.opentelemetry.instrumentation.agent.okhttp.OkHttpClientPlugin} from okhttp3-agent at compile
 * time; this class must live in okhttp3-library so woven OkHttp bytecode can resolve it at runtime.
 */
public class OkHttpClientAdvice {

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) OkHttpClient.Builder builder) {
        OkHttpSingletons.applyClientInstrumentation(builder);
    }
}
