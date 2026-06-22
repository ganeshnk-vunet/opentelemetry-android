/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.compose.nav2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavController

/**
 * Installs Compose Navigation 2 destination observation for the provided [navController].
 *
 * Call this once per [NavController] from a stable place in your Compose tree.
 * The listener is attached while the composable is active and removed automatically on dispose.
 */
@Composable
@Suppress("FunctionName")
fun VunetNav2Observer(navController: NavController) {
    val rum = NavObserverRumHolder.current() ?: return
    val collector = remember(navController, rum) { ComposeNav2Collector(rum) }
    DisposableEffect(navController, collector) {
        NavObserverCollectorHolder.set(navController, collector)
        navController.addOnDestinationChangedListener(collector)
        onDispose {
            navController.removeOnDestinationChangedListener(collector)
            NavObserverCollectorHolder.remove(navController)
        }
    }
}

/**
 * Wraps an [onBack] callback and records a back-press trigger when possible.
 *
 * Returns a new lambda that records the back press and then invokes [onBack]. You must call this
 * returned lambda (not the original [onBack]) for the pop to be attributed to a back press when
 * [VunetNav2Observer] is active for the same [navController]; invoking [onBack] directly bypasses
 * attribution.
 *
 * Example:
 * ```
 * val onBack = rememberVunetOnBack(navController) { navController.popBackStack() }
 * BackHandler(onBack = onBack)
 * // ...
 * IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
 * ```
 */
@Composable
@Suppress("FunctionName")
fun rememberVunetOnBack(
    navController: NavController,
    onBack: () -> Unit,
): () -> Unit {
    val currentOnBack = rememberUpdatedState(onBack)
    return remember(navController) {
        {
            NavObserverCollectorHolder.current(navController)?.recordBackPress()
            currentOnBack.value()
        }
    }
}
