/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav2

import java.util.WeakHashMap

internal object NavObserverCollectorHolder {
    private val collectors: MutableMap<Any, ComposeNav2Collector> = WeakHashMap()

    @Synchronized
    fun set(
        navController: Any,
        collector: ComposeNav2Collector,
    ) {
        collectors[navController] = collector
    }

    @Synchronized
    fun current(navController: Any): ComposeNav2Collector? = collectors[navController]

    @Synchronized
    fun remove(navController: Any) {
        collectors.remove(navController)
    }

    @Synchronized
    fun clear() {
        collectors.clear()
    }
}
