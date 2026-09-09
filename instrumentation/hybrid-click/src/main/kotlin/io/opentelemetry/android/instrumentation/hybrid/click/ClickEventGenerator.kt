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
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTION_MODE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_END_DATE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_SELECTED_DATE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_START_DATE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_VALUE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_CONTROL_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_GESTURE_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_INTERACTION_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_CHECKED
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_SOURCE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ATTR_WIDGET_TYPE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.ControlValue
import io.opentelemetry.android.instrumentation.hybrid.click.shared.GestureType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.SOURCE_COMPOSE
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_SLIDER
import io.opentelemetry.android.instrumentation.hybrid.click.shared.WIDGET_TYPE_UNKNOWN
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapGestureClassifier
import io.opentelemetry.android.instrumentation.hybrid.click.shared.TapTarget
import io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveInteractionType
import io.opentelemetry.android.instrumentation.hybrid.click.shared.resolveSelectionMode
import io.opentelemetry.android.instrumentation.hybrid.click.view.ViewTapTargetDetector
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Generates `ui.interaction` spans for qualified tap gestures in hybrid View/Compose screens.
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
            // Optional: an older detector build may not have nodeToType. Resolve it leniently so a
            // missing method only degrades `type` to "unknown" rather than failing the whole bridge
            // (which would disable all Compose click detection).
            val nodeToTypeMethod =
                runCatching {
                    findMangledMethod(
                        detectorClass = detectorClass,
                        methodBaseName = "nodeToType",
                        parameterType = layoutNodeClass,
                    )
                }.getOrNull()
            // Same leniency as nodeToType, for the same reason: a detector build without it should
            // cost the value attribute, not all Compose click detection.
            val nodeToNormalizedValueMethod =
                runCatching {
                    findMangledMethod(
                        detectorClass = detectorClass,
                        methodBaseName = "nodeToNormalizedValue",
                        parameterType = layoutNodeClass,
                    )
                }.getOrNull()
            ReflectiveComposeDetectorBridge(
                detector = detector,
                findTapTargetMethod = findTapTargetMethod,
                nodeToNameMethod = nodeToNameMethod,
                nodeToLabelMethod = nodeToLabelMethod,
                nodeToPositionMethod = nodeToPositionMethod,
                nodeToTypeMethod = nodeToTypeMethod,
                nodeToNormalizedValueMethod = nodeToNormalizedValueMethod,
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
                longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
            }
        window.callback = WindowCallbackWrapper(currentCallback, window, this)
        RumDiagnostics.d { "hybridClick: window callback attached" }
    }

    /**
     * Consumes motion events for [window], qualifies tap gestures, resolves a tap target, and emits
     * `ui.interaction`. The target is always resolved against the decorView of the window that received
     * the touch, keeping multi-window tracking correct.
     */
    fun generateClick(
        window: Window,
        motionEvent: MotionEvent?,
    ) {
        val event = motionEvent ?: return
        val tapGestureClassifier = tapGestureClassifiers[window] ?: return
        val gestureType = tapGestureClassifier.classify(event) ?: return

        if (gestureType == GestureType.DRAG) {
            emitDrag(window, tapGestureClassifier)
            return
        }

        ActiveInteractionContext.clear()

        val target = resolveTarget(window, event.x, event.y) ?: return
        emitSpan(target, gestureType)
    }

    /**
     * Handles a gesture that left the touch slop, emitting a span only when it actually moved a
     * range control.
     *
     * The target is resolved at the point the finger went *down*, not up: a slider drag routinely
     * ends outside the control's own bounds, and the up position would resolve to whatever is there
     * instead. Resolution happens here rather than for every `ACTION_DOWN` so an ordinary tap pays
     * nothing for this path.
     *
     * [ActiveInteractionContext] is deliberately *not* cleared on this path. Every scroll and fling
     * in the app arrives here, and clearing would wipe the active click context mid-gesture — a user
     * who taps "Pay" and then flicks the screen while the request is in flight would silently lose
     * the parenting that makes click-to-network correlation work.
     */
    private fun emitDrag(
        window: Window,
        tapGestureClassifier: TapGestureClassifier,
    ) {
        val target = resolveTarget(window, tapGestureClassifier.downX, tapGestureClassifier.downY) ?: return
        if (target.type != WIDGET_TYPE_SLIDER || !target.isTracking) {
            return
        }
        emitSpan(target, GestureType.DRAG)
    }

    /** Resolves the target under ([x], [y]), preferring Compose and falling back to the View tree. */
    private fun resolveTarget(
        window: Window,
        x: Float,
        y: Float,
    ): TapTarget? =
        findComposeTarget(window.decorView, x, y)
            ?: viewTapTargetDetector.findTapTarget(window.decorView, x, y)

    private fun emitSpan(
        target: TapTarget,
        gestureType: GestureType,
    ) {
        RumDiagnostics.d {
            "hybridClick: ${gestureType.value} -> Click span target=${target.widgetId} source=${target.source}"
        }

        val spanBuilder =
            tracer.spanBuilder(RumConstants.UI_INTERACTION_SPAN_NAME)
                .setNoParent()
                .setAttribute(AppIncubatingAttributes.APP_WIDGET_ID, target.widgetId)
                .setAttribute(AppIncubatingAttributes.APP_WIDGET_NAME, target.label)
                .setAttribute(AppIncubatingAttributes.APP_SCREEN_COORDINATE_X, target.x)
                .setAttribute(AppIncubatingAttributes.APP_SCREEN_COORDINATE_Y, target.y)
                .setAttribute(ATTR_WIDGET_SOURCE, target.source)
                .setAttribute(ATTR_WIDGET_TYPE, target.type)
                .setAttribute(ATTR_CONTROL_TYPE, target.type)
                // A detector-supplied kind wins: some interactions are invisible in the widget
                // kind alone -- see TapTarget.interactionKind.
                .setAttribute(
                    ATTR_INTERACTION_TYPE,
                    target.interactionKind ?: resolveInteractionType(target.type, gestureType),
                )
                .setAttribute(ATTR_GESTURE_TYPE, gestureType.value)
        resolveSelectionMode(target.type)?.let { spanBuilder.setAttribute(ATTR_CONTROL_SELECTION_MODE, it) }
        val span = spanBuilder.startSpan()

        val token = ActiveInteractionContext.begin(span)
        scheduleContextEnd(token)

        // A pre-gesture snapshot is only meaningful for a drag -- see TapTarget.valueIsPreGesture.
        val valueProvider =
            target.valueProvider?.takeUnless { target.valueIsPreGesture && gestureType != GestureType.DRAG }
        if (valueProvider == null) {
            span.end()
        } else {
            // The widget has not processed this gesture yet: WindowCallbackWrapper calls us *before*
            // delegating the touch. A CompoundButton flips in PerformClick, which View.onTouchEvent
            // *posts* on ACTION_UP rather than running inline, so re-posting from inside a posted
            // runnable (a double post) guarantees the read lands after that flip. A range control
            // needs only one post — AbsSeekBar overrides onTouchEvent entirely and updates progress
            // inline, so nothing is posted for it — but the extra tick is harmless and one path is
            // worth more than a second timing rule. Reading inline would report the pre-gesture
            // value, which for a tap-seek is wrong every single time.
            // The span ends after the read; ActiveInteractionContext stays current for
            // activeContextWindowMillis independently.
            mainHandler.post {
                mainHandler.post {
                    try {
                        when (val value = valueProvider()) {
                            is ControlValue.Checked -> span.setAttribute(ATTR_WIDGET_CHECKED, value.checked)
                            is ControlValue.Percentage -> span.setAttribute(ATTR_CONTROL_VALUE, value.percent)
                            is ControlValue.SelectedDate ->
                                span.setAttribute(ATTR_CONTROL_SELECTED_DATE, value.dayOffset)

                            is ControlValue.SelectedDateRange -> {
                                // Either end may be absent: a range picker can be confirmed with only
                                // one side chosen.
                                value.startDayOffset?.let { span.setAttribute(ATTR_CONTROL_START_DATE, it) }
                                value.endDayOffset?.let { span.setAttribute(ATTR_CONTROL_END_DATE, it) }
                            }

                            null -> Unit
                        }
                    } catch (throwable: Throwable) {
                        RumDiagnostics.d { "hybridClick: swallowed error reading control value: ${throwable.message}" }
                    }
                    // Always end the span, even if the read above failed, so it never leaks.
                    span.end()
                }
            }
        }
    }

    private fun scheduleContextEnd(token: Long) {
        mainHandler.postDelayed({ ActiveInteractionContext.end(token) }, activeContextWindowMillis)
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
    private val nodeToTypeMethod: Method?,
    private val nodeToNormalizedValueMethod: Method?,
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
            val type = nodeToTypeMethod?.invoke(detector, node) as? String ?: WIDGET_TYPE_UNKNOWN
            // Captured now, not deferred: a Compose slider's value reaches its semantics only after
            // a composition pass, which runs on a frame boundary rather than a message boundary, so
            // a posted re-read would not reliably see it. That makes it accurate to within the last
            // frame of a drag but pre-gesture for a tap, which valueIsPreGesture below tells the
            // emitter to respect.
            val percent =
                if (type == WIDGET_TYPE_SLIDER) {
                    nodeToNormalizedValueMethod?.invoke(detector, node) as? Double
                } else {
                    null
                }
            TapTarget(
                source = SOURCE_COMPOSE,
                widgetId = node.hashCode().toString(),
                widgetName = widgetName,
                label = label,
                x = nodeX,
                y = nodeY,
                type = type,
                // A Compose node has no pressed flag to read; the drag guard relies on the node
                // having been hit-tested at the down point, which findTapTarget already enforces.
                isTracking = true,
                valueProvider = percent?.let { p -> { ControlValue.Percentage(p) } },
                valueIsPreGesture = true,
            )
        } catch (_: Throwable) {
            null
        }
}
