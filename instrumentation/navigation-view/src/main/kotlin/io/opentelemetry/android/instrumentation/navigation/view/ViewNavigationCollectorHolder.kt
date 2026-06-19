/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.view

internal object ViewNavigationCollectorHolder {
    @Volatile
    private var collector: ViewNavigationCollector? = null

    fun set(collector: ViewNavigationCollector) {
        this.collector = collector
    }

    fun current(): ViewNavigationCollector? = collector

    fun clear() {
        collector = null
    }
}
