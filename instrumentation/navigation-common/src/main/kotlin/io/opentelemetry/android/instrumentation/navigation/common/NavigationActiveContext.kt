/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.api.trace.Span

/**
 * Retains the most recent [ui.navigation][NavigationConstants.SPAN_NAME] span as the active
 * OpenTelemetry context until the next navigation, a new click interaction, or [clear].
 */
internal object NavigationActiveContext {
    fun activate(span: Span) {
        ActiveInteractionContext.activate(span)
    }

    fun clear() {
        ActiveInteractionContext.clear()
    }
}
