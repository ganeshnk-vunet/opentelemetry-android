/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.common.interaction

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test

class ActiveInteractionHolderTest {
    private val key: ContextKey<String> = ContextKey.named("test-interaction")

    @After
    fun tearDown() {
        // Holder is process-global; reset between tests by expiring it.
        ActiveInteractionHolder.set(Context.root(), 0)
        ActiveInteractionHolder.current()
    }

    @Test
    fun current_returnsNull_whenNothingSet() {
        ActiveInteractionHolder.clear(Context.root())
        // Expire any leftover entry.
        ActiveInteractionHolder.set(Context.root(), 0)
        assertThat(ActiveInteractionHolder.current()).isNull()
    }

    @Test
    fun current_returnsContext_whileUnexpired() {
        val context = Context.root().with(key, "value")
        ActiveInteractionHolder.set(context, 5_000)
        assertThat(ActiveInteractionHolder.current()).isSameAs(context)
    }

    @Test
    fun current_returnsNull_afterExpiry() {
        val context = Context.root().with(key, "value")
        ActiveInteractionHolder.set(context, 0)
        assertThat(ActiveInteractionHolder.current()).isNull()
    }

    @Test
    fun clear_removesActiveInteraction() {
        val context = Context.root().with(key, "value")
        ActiveInteractionHolder.set(context, 5_000)
        ActiveInteractionHolder.clear(context)
        assertThat(ActiveInteractionHolder.current()).isNull()
    }

    @Test
    fun clear_isIdentityChecked_doesNotClobberNewerInteraction() {
        val older = Context.root().with(key, "older")
        val newer = Context.root().with(key, "newer")
        ActiveInteractionHolder.set(older, 5_000)
        ActiveInteractionHolder.set(newer, 5_000)

        // The older interaction ending must not remove the newer one.
        ActiveInteractionHolder.clear(older)

        assertThat(ActiveInteractionHolder.current()).isSameAs(newer)
    }
}
