/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.dsl.instrumentation

import io.opentelemetry.android.agent.FakeClock
import io.opentelemetry.android.agent.FakeInstrumentationLoader
import io.opentelemetry.android.agent.dsl.OpenTelemetryConfiguration
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.ConfigurableHybridClickInstrumentation
import android.content.Context
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

internal class HybridClickConfigurationTest {
    @Test
    fun `activeContextWindowMillis sets value when instrumentation is present`() {
        val instrumentation = FakeHybridClickInstrumentation()
        val loader = FakeInstrumentationLoader().apply { register(instrumentation) }
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = loader)

        configuration.instrumentations {
            hybridClick {
                activeContextWindowMillis(1_500L)
            }
        }

        assertEquals(1_500L, instrumentation.activeContextWindowMillis)
    }

    @Test
    fun `activeContextWindowMillis does nothing when instrumentation is absent`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        configuration.instrumentations {
            hybridClick {
                activeContextWindowMillis(800L)
            }
        }
    }

    @Test
    fun `interactionFallbackTtlMillis sets value when instrumentation is present`() {
        val instrumentation = FakeHybridClickInstrumentation()
        val loader = FakeInstrumentationLoader().apply { register(instrumentation) }
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = loader)

        configuration.instrumentations {
            hybridClick {
                interactionFallbackTtlMillis(2_000L)
            }
        }

        assertEquals(2_000L, instrumentation.interactionFallbackTtlMillis)
    }

    @Test
    fun `interactionFallbackTtlMillis rejects non-positive values`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        assertThrows(IllegalArgumentException::class.java) {
            configuration.instrumentations {
                hybridClick {
                    interactionFallbackTtlMillis(0L)
                }
            }
        }
    }

    @Test
    fun `activeContextWindowMillis rejects non-positive values`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        assertThrows(IllegalArgumentException::class.java) {
            configuration.instrumentations {
                hybridClick {
                    activeContextWindowMillis(0L)
                }
            }
        }
    }

    @Test
    fun `enabled false suppresses hybrid click`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())

        configuration.instrumentations {
            hybridClick {
                enabled(false)
            }
        }

        assertTrue(configuration.rumConfig.isSuppressed("hybrid.click"))
    }

    @Test
    fun `enabled true allows hybrid click`() {
        val configuration = OpenTelemetryConfiguration(clock = FakeClock(), instrumentationLoader = FakeInstrumentationLoader())
        configuration.rumConfig.suppressInstrumentation("hybrid.click")

        configuration.instrumentations {
            hybridClick {
                enabled(true)
            }
        }

        assertFalse(configuration.rumConfig.isSuppressed("hybrid.click"))
    }
}

private class FakeHybridClickInstrumentation : AndroidInstrumentation, ConfigurableHybridClickInstrumentation {
    var activeContextWindowMillis: Long? = null
    var interactionFallbackTtlMillis: Long? = null

    override val name: String = "hybrid.click"

    override fun install(
        context: Context,
        openTelemetryRum: OpenTelemetryRum,
    ) {}

    override fun setActiveContextWindowMillis(value: Long) {
        activeContextWindowMillis = value
    }

    override fun setInteractionFallbackTtlMillis(value: Long) {
        interactionFallbackTtlMillis = value
    }
}
