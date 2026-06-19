/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.agent.concurrency.coroutines

import io.opentelemetry.instrumentation.library.concurrency.coroutines.CoroutineContextAdvice
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import net.bytebuddy.asm.Advice
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatchers

internal class CoroutineContextPlugin : Plugin {

    override fun apply(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classFileLocator: ClassFileLocator,
    ): DynamicType.Builder<*> =
        builder.visit(
            Advice
                .to(CoroutineContextAdvice::class.java)
                .on(
                    ElementMatchers
                        .named<MethodDescription>("newCoroutineContext")
                        .and(ElementMatchers.takesArguments(2))
                        .and(ElementMatchers.takesArgument(0, CoroutineContext::class.java))
                        .and(ElementMatchers.takesArgument(1, CoroutineContext::class.java)),
                ),
        )

    @Throws(IOException::class)
    override fun close() {
        // No operation.
    }

    override fun matches(target: TypeDescription): Boolean =
        target.typeName == "kotlinx.coroutines.CoroutineContextKt"
}
