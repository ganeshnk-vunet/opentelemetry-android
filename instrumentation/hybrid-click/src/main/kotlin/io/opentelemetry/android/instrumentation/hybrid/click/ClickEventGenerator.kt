/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.hybrid.click

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_SOURCE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SOURCE_COMPOSE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapGestureClassifier
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTarget
import io.opentelemetry.android.instrumentation.hybrid.click.shared.UI_CLICK_SPAN_NAME
import io.opentelemetry.android.instrumentation.hybrid.click.view.ViewTapTargetDetector
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Generates `ui.click` spans for qualified tap gestures in hybrid View/Compose screens.
 *
 * ## Why this implementation looks unusual
 * Hybrid click needs Compose node metadata, but direct typed wiring to Compose internals in this
 * module previously produced bytecode that AnimalSniffer flagged (`error.NonExistentClass`).
 *
 * To keep hybrid-click publishable while still resolving Compose targets at runtime:
 * 1) Compose detector returns Compose node info behind a reflection boundary.
 * 2) This class maps that reflected node into the stable hybrid [TapTarget] model.
 * 3) View fallback remains intact when Compose is unavailable or detector resolution fails.
 */
internal class ClickEventGenerator(
    private val tracer: Tracer,
    private val viewTapTargetDetector: ViewTapTargetDetector = ViewTapTargetDetector(),
    private val activeContextWindowMillis: Long = DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Per-window gesture state. Hybrid-click tracks several windows at once (the Activity window
     * plus any dialog windows on top of it), so each gets its own classifier keyed by [Window];
     * weak keys let a window's entry drop if tracking is ever left unbalanced.
     */
    private val tapGestureClassifiers = WeakHashMap<Window, TapGestureClassifier>()

    private val composeTapTargetDetector: ComposeDetectorBridge? by lazy {
        loadComposeDetector()
    }

    /**
     * Lazily loads Compose detector wiring via reflection.
     *
     * Hybrid-click keeps Compose internals behind reflection so this module can stay resilient
     * when Compose internals/signatures vary across app/toolchain combinations.
     *
     * Historical context:
     * - Initial direct reflection by exact method names failed at runtime with:
     *   `NoSuchMethodException: ... nodeToName(LayoutNode)`.
     * - Cause: Kotlin `internal` methods may be name-mangled in bytecode.
     * - Resolution: method lookup now supports base-name + mangled-name matching.
     */
    private fun loadComposeDetector(): ComposeDetectorBridge? {
        return try {
            val detectorClass =
                Class.forName(
                    "io.opentelemetry.android.instrumentation.hybrid.click.compose.ComposeTapTargetDetector",
                )
            val detector = detectorClass.getDeclaredConstructor().newInstance()
            val layoutNodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
            val findTapTargetMethod =
                detectorClass.getMethod(
                    "findTapTarget",
                    View::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
            val nodeToNameMethod =
                findMangledMethod(
                    detectorClass = detectorClass,
                    methodBaseName = "nodeToName",
                    parameterType = layoutNodeClass,
                )
            val nodeToLabelMethod = detectorClass.getDeclaredMethod("nodeToLabel", layoutNodeClass).apply { isAccessible = true }
            val nodeToPositionMethod =
                findMangledMethod(
                    detectorClass = detectorClass,
                    methodBaseName = "nodeToPosition",
                    parameterType = layoutNodeClass,
                )
            ReflectiveComposeDetectorBridge(
                detector = detector,
                findTapTargetMethod = findTapTargetMethod,
                nodeToNameMethod = nodeToNameMethod,
                nodeToLabelMethod = nodeToLabelMethod,
                nodeToPositionMethod = nodeToPositionMethod,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves Kotlin-internal method names that may be mangled in bytecode (e.g. `foo$module`).
     *
     * We match by base name + parameter type to avoid hardcoding mangled suffixes.
     *
     * This is required because exact `getDeclaredMethod("nodeToName", ...)` can fail depending on
     * how Kotlin emits internal method names for the consuming toolchain.
     */
    private fun findMangledMethod(
        detectorClass: Class<*>,
        methodBaseName: String,
        parameterType: Class<*>,
    ): Method =
        detectorClass.declaredMethods.firstOrNull {
            (it.name == methodBaseName || it.name.startsWith("$methodBaseName$")) &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == parameterType
        }?.apply {
            isAccessible = true
        } ?: throw NoSuchMethodException("$methodBaseName([${parameterType.name}])")

    /**
     * Installs the wrapped window callback and initializes gesture thresholds for [window].
     *
     * Safe to call repeatedly and for multiple windows at once (e.g. an Activity window plus any
     * dialog windows stacked on top); each tracked window keeps its own gesture state.
     */
    fun startTracking(window: Window) {
        val currentCallback = window.callback ?: return
        if (currentCallback is WindowCallbackWrapper) {
            return
        }
        tapGestureClassifiers[window] =
            TapGestureClassifier().apply {
                touchSlopPx =
                    ViewConfiguration.get(window.decorView.context).scaledTouchSlop.toFloat()
            }
        window.callback = WindowCallbackWrapper(currentCallback, window, this)
        RumDiagnostics.d { "hybridClick: window callback attached" }
    }

    /**
     * Consumes motion events for [window], qualifies tap gestures, resolves a tap target, and emits
     * `ui.click`. The target is always resolved against the decorView of the window that received
     * the touch, keeping multi-window tracking correct.
     */
    fun generateClick(
        window: Window,
        motionEvent: MotionEvent?,
    ) {
        val event = motionEvent ?: return
        val tapGestureClassifier = tapGestureClassifiers[window] ?: return
        if (!tapGestureClassifier.shouldEmitClick(event)) {
            return
        }

        ActiveInteractionContext.clear()

        val target =
            findComposeTarget(window.decorView, event.x, event.y)
                ?: viewTapTargetDetector.findTapTarget(window.decorView, event.x, event.y)
                ?: return

        RumDiagnostics.d {
            "hybridClick: tap -> Click span target=${target.widgetId} source=${target.source}"
        }

        val span =
            tracer.spanBuilder(UI_CLICK_SPAN_NAME)
                .setNoParent()
                .setAttribute(AppIncubatingAttributes.APP_WIDGET_ID, target.widgetId)
                .setAttribute(AppIncubatingAttributes.APP_WIDGET_NAME, target.label)
                .setAttribute(AppIncubatingAttributes.APP_SCREEN_COORDINATE_X, target.x)
                .setAttribute(AppIncubatingAttributes.APP_SCREEN_COORDINATE_Y, target.y)
                .setAttribute(ATTR_WIDGET_SOURCE, target.source)
                .startSpan()

        val token = ActiveInteractionContext.begin(span)
        val endSpan =
            Runnable {
                ActiveInteractionContext.end(token)
                span.end()
            }

        val checkedStateProvider = target.checkedStateProvider
        if (checkedStateProvider == null) {
            mainHandler.postDelayed(endSpan, activeContextWindowMillis)
        } else {
            // A CompoundButton flips in PerformClick, which View.onTouchEvent *posts* on ACTION_UP
            // rather than running inline. Re-posting from inside a posted runnable (a double post)
            // guarantees the read runs after that flip; reading inline would observe the pre-tap
            // state. The span end is scheduled only after the read, so the attribute is always
            // recorded before the span closes regardless of activeContextWindowMillis.
            mainHandler.post {
                mainHandler.post {
                    try {
                        checkedStateProvider()?.let { checked ->
                            span.setAttribute(ATTR_WIDGET_CHECKED, checked)
                        }
                    } catch (throwable: Throwable) {
                        RumDiagnostics.d { "hybridClick: swallowed error reading toggle state: ${throwable.message}" }
                    }
                    // Always schedule the end, even if the read above failed, so the span never leaks.
                    mainHandler.postDelayed(endSpan, activeContextWindowMillis)
                }
            }
        }
    }

    /**
     * Attempts Compose target resolution first; caller can fallback to View resolution.
     */
    private fun findComposeTarget(
        rootView: View,
        x: Float,
        y: Float,
    ): TapTarget? = composeTapTargetDetector?.findTapTarget(rootView, x, y)

    /**
     * Restores [window]'s original callback and clears its gesture state, detaching tap tracking
     * for that specific window.
     */
    fun stopTracking(window: Window) {
        val callback = window.callback
        if (callback is WindowCallbackWrapper) {
            window.callback = callback.unwrap()
        }
        tapGestureClassifiers.remove(window)?.reset()
    }

    private companion object {
        const val DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS = 500L
    }

}

/**
 * Small typed boundary used by [ClickEventGenerator] to query Compose tap targets.
 *
 * This keeps reflection details out of core click-generation flow and avoids exposing `Any`
 * through the main logic.
 *
 * It also documents the intentional separation: Compose detector concerns stay encapsulated while
 * click-span orchestration remains strongly typed.
 */
private interface ComposeDetectorBridge {
    /**
     * Returns a Compose-derived [TapTarget], or `null` when no Compose target is available.
     */
    fun findTapTarget(
        rootView: View,
        x: Float,
        y: Float,
    ): TapTarget?
}

/**
 * Reflection-backed adapter for Compose detector internals.
 *
 * Compose detector methods may be internal/mangled; methods are pre-resolved once in
 * [ClickEventGenerator.loadComposeDetector] and invoked here for runtime target conversion.
 *
 * This is the final form after debugging real app failures where reflection by exact method names
 * caused bridge-load failure and disabled Compose click detection entirely.
 */
private class ReflectiveComposeDetectorBridge(
    private val detector: Any,
    private val findTapTargetMethod: Method,
    private val nodeToNameMethod: Method,
    private val nodeToLabelMethod: Method,
    private val nodeToPositionMethod: Method,
) : ComposeDetectorBridge {
    /**
     * Invokes reflected detector methods and maps the Compose node to hybrid [TapTarget].
     */
    override fun findTapTarget(
        rootView: View,
        x: Float,
        y: Float,
    ): TapTarget? =
        try {
            val node = findTapTargetMethod.invoke(detector, rootView, x, y) ?: return null
            val widgetName = nodeToNameMethod.invoke(detector, node) as? String ?: node.hashCode().toString()
            val label = nodeToLabelMethod.invoke(detector, node) as? String ?: widgetName
            val position = nodeToPositionMethod.invoke(detector, node) as? Pair<*, *>
            val nodeX = (position?.first as? Long) ?: 0L
            val nodeY = (position?.second as? Long) ?: 0L
            TapTarget(
                source = SOURCE_COMPOSE,
                widgetId = node.hashCode().toString(),
                widgetName = widgetName,
                label = label,
                x = nodeX,
                y = nodeY,
            )
        } catch (_: Throwable) {
            null
        }
}
