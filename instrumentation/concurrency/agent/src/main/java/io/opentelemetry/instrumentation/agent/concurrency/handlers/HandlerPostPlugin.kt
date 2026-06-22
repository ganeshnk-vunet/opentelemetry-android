/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.agent.concurrency.handlers

import io.opentelemetry.instrumentation.library.concurrency.handlers.HandlerPostAdvice
import java.io.IOException
import java.lang.Runnable
import net.bytebuddy.asm.Advice
import net.bytebuddy.build.Plugin
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatchers

internal class HandlerPostPlugin : Plugin {

    override fun apply(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        classFileLocator: ClassFileLocator,
    ): DynamicType.Builder<*> =
        builder.visit(
            Advice
                .to(HandlerPostAdvice::class.java)
                .on(
                    ElementMatchers
                        .nameStartsWith<MethodDescription>("post")
                        .and(ElementMatchers.takesArgument(0, Runnable::class.java)),
                ),
        )

    @Throws(IOException::class)
    override fun close() {
        // No operation.
    }

    override fun matches(target: TypeDescription): Boolean = target.typeName == "android.os.Handler"
}
