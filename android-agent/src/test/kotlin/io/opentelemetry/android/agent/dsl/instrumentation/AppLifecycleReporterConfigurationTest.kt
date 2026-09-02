/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.dsl.instrumentation

import io.opentelemetry.android.agent.FakeClock
import io.opentelemetry.android.agent.FakeInstrumentationLoader
import io.opentelemetry.android.agent.dsl.OpenTelemetryConfiguration
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

internal class AppLifecycleReporterConfigurationTest {
    /**
     * The loader is deliberately empty: suppression must not depend on resolving the
     * instrumentation instance first, or `enabled(false)` would silently do nothing wherever the
     * instrumentation is not discoverable and telemetry the caller believes they switched off
     * would keep being emitted.
     */
    @Test
    fun `enabled false suppresses app lifecycle even with an empty loader`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        configuration.instrumentations {
            appLifecycle {
                enabled(false)
            }
        }

        assertTrue(configuration.rumConfig.isSuppressed("applifecycle"))
    }

    @Test
    fun `enabled true allows app lifecycle`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())
        configuration.rumConfig.suppressInstrumentation("applifecycle")

        configuration.instrumentations {
            appLifecycle {
                enabled(true)
            }
        }

        assertFalse(configuration.rumConfig.isSuppressed("applifecycle"))
    }

    /** The constant in the DSL must match the name the instrumentation actually reports. */
    @Test
    fun `suppression name matches the instrumentation name`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        configuration.instrumentations {
            appLifecycle {
                enabled(false)
            }
        }

        assertTrue(
            configuration.rumConfig.isSuppressed(
                io.opentelemetry.android.instrumentation.applifecycle.AppLifecycleInstrumentation().name,
            ),
        )
    }
}
