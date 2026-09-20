/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.instrumentation.navigation.common

import io.opentelemetry.android.common.RumConstants.SCREEN_NAME_KEY
import io.opentelemetry.android.common.RumDiagnostics
import io.opentelemetry.android.common.internal.instrumentation.ActiveInteractionContext
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DESTINATION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_DURATION_MS_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_ENTRY_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_IS_INITIAL_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_NAME_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_SOURCE_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_STACK_DEPTH_AFTER_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_STACK_DEPTH_BEFORE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TIMESTAMP_NS_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRIGGER_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.NAVIGATION_TRANSITION_TYPE_KEY
import io.opentelemetry.android.instrumentation.navigation.common.NavigationConstants.SPAN_NAME
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionCandidate
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTransitionType
import io.opentelemetry.android.instrumentation.navigation.common.models.NavigationTrigger
import io.opentelemetry.api.trace.Tracer

class NavigationSpanEmitter(
    private val tracer: Tracer,
) {
    fun emit(candidate: NavigationTransitionCandidate) {
        emit(candidate, navigationTrigger = null)
    }

    fun emit(
        candidate: NavigationTransitionCandidate,
        navigationTrigger: String?,
    ) {
        // Read before the trigger is resolved below, which needs to know whether a click
        // interaction is live. Side-effect free, so reading it earlier than the setParent use is
        // behaviorally identical.
        val interactionContext = ActiveInteractionContext.rootContext()

        val spanBuilder =
            tracer
                .spanBuilder(SPAN_NAME)
                .setAttribute(NAVIGATION_DESTINATION_TYPE_KEY, candidate.destination.type.name.lowercase())
                .setAttribute(NAVIGATION_DESTINATION_NAME_KEY, candidate.destination.name)
                .setAttribute(NAVIGATION_TRANSITION_TYPE_KEY, candidate.transitionType.value)
                .setAttribute(NAVIGATION_ENTRY_TYPE_KEY, candidate.entryType.value)
                .setAttribute(NAVIGATION_TIMESTAMP_NS_KEY, candidate.timestampNanos)
                .setAttribute(NAVIGATION_IS_INITIAL_KEY, NavigationColdStartTracker.consumeIsInitial())

        resolveTrigger(navigationTrigger, interactionContext != null)?.let {
            spanBuilder.setAttribute(NAVIGATION_TRIGGER_KEY, it)
        }

        spanBuilder.setAttribute(NAVIGATION_DURATION_MS_KEY, resolveDurationMs(candidate))

        candidate.stackDepthBefore?.let {
            spanBuilder.setAttribute(NAVIGATION_STACK_DEPTH_BEFORE_KEY, it.toLong())
        }
        candidate.stackDepthAfter?.let {
            spanBuilder.setAttribute(NAVIGATION_STACK_DEPTH_AFTER_KEY, it.toLong())
        }

        candidate.source?.let {
            spanBuilder
                .setAttribute(NAVIGATION_SOURCE_TYPE_KEY, it.type.name.lowercase())
                .setAttribute(NAVIGATION_SOURCE_NAME_KEY, it.name)
        }

        interactionContext?.let { spanBuilder.setParent(it) }

        val span = spanBuilder.startSpan()
        // Set screen.name after start so it wins over default attribute appenders.
        span.setAttribute(SCREEN_NAME_KEY, candidate.destination.name)
        span.end()
        if (interactionContext != null) {
            NavigationActiveContext.activate(span)
        }
        RumDiagnostics.d {
            "navigation: span dest=${candidate.destination.name} type=${candidate.destination.type.name.lowercase()}"
        }
    }

    /**
     * Reports [NavigationTrigger.USER_TAP] when the navigation happened inside a live
     * click-interaction window and nothing more specific already explains it.
     *
     * The collectors cannot make this call themselves — they have no view of the interaction
     * context. They report `unknown` for forward transitions, and `back_press`/`programmatic` for
     * pops depending on whether a back press was recorded. Two of those three are upgraded here:
     * - `unknown` — a forward transition that happened while a tap was live.
     * - `programmatic` — only ever produced for a pop with no recorded back press, which inside a
     *   click window is a tap-driven pop: a toolbar "up" or a "close" button. The pop itself is
     *   still recorded by `navigation.transition.type`, so naming the trigger `user_tap` loses
     *   nothing and stops the commonest tap-driven back navigation reading as code-driven.
     *
     * [NavigationTrigger.BACK_PRESS] is never upgraded: a system back press is a real back press
     * even if a tap happened to be live, and it is the more specific fact of the two.
     *
     * A non-null [hasLiveInteraction] means exactly "a tap opened a window that has not expired":
     * `ClickEventGenerator` is the only production caller of `ActiveInteractionContext.begin`, and
     * it schedules its own expiry.
     *
     * Known limit: the window is not consumed here, so a genuinely programmatic navigation landing
     * inside the window of an unrelated tap is also labelled `user_tap`. Consuming it would need
     * `ActiveInteractionContext` to expose a "claim" operation, which is owned by hybrid-click and
     * shared with the HTTP instrumentations.
     */
    private fun resolveTrigger(
        navigationTrigger: String?,
        hasLiveInteraction: Boolean,
    ): String? {
        val isUpgradable =
            navigationTrigger == null ||
                navigationTrigger == NavigationTrigger.UNKNOWN.value ||
                navigationTrigger == NavigationTrigger.PROGRAMMATIC.value
        return if (isUpgradable && hasLiveInteraction) {
            NavigationTrigger.USER_TAP.value
        } else {
            navigationTrigger
        }
    }

    /**
     * Milliseconds from the user action that caused this navigation to the moment the destination
     * was committed, or [NOT_ATTRIBUTABLE_MS] when no action can be attributed to it.
     *
     * Two sources, in order of specificity:
     * - [NavigationTransitionCandidate.intentAtNanos], the back press a collector recorded, **and
     *   only on a [NavigationTransitionType.POP]**.
     * - The most recent interaction start, which is the tap that began it.
     *
     * A back press wins when both apply, for the same reason `resolveTrigger` never upgrades
     * `back_press` to `user_tap`: it is the more specific fact.
     *
     * The pop check is what stops a back press timing a screen it did not open. A collector holds
     * the press until some transition consumes it, and a press does not always produce a pop — it
     * may dismiss a dialog, or the user may change their mind and tap forward instead. Without the
     * check, the next transition of *any* direction inherited that timestamp, so a forward
     * navigation from a later tap reported the time since the abandoned back press: a long
     * navigation that never happened. A back press can only explain a pop, so on a push or replace
     * the tap below is the right source.
     *
     * **Neither source is read through the interaction *parenting* window, and that is the point.**
     * `ActiveInteractionContext` drops its parent context after
     * `ClickEventGenerator.DEFAULT_ACTIVE_CONTEXT_WINDOW_MILLIS` (500 ms), and the back-press
     * trigger signal expires after `NavigationTriggerResolver.BACK_PRESS_SIGNAL_TTL_NANOS` (1 s).
     * Those windows exist to decide *parenting* and *trigger naming*, where a stale signal is
     * actively harmful. Timing is the opposite case: a navigation that takes three seconds is
     * precisely the one worth measuring, and reading the start time through a 500 ms window meant
     * every slow navigation reported no duration at all rather than a large one — the metric
     * showed only the navigations nobody needed to investigate.
     *
     * Staleness is bounded by [MAX_ATTRIBUTION_NANOS] instead, chosen to be far longer than any
     * navigation worth recording but short enough that a navigation with no user action behind it
     * cannot inherit a timestamp from some unrelated earlier tap. Beyond it the result is
     * [NOT_ATTRIBUTABLE_MS] rather than a clamp, because a clamped value would be
     * indistinguishable from a real navigation of that length.
     *
     * [NOT_ATTRIBUTABLE_MS] likewise when nothing applies at all, and when the result would be
     * negative — a destination committed before the action that caused it is not a duration.
     *
     * A tap is claimed by the first navigation that uses it
     * (`ActiveInteractionContext.lastInteractionStartedAtNanos`), so it cannot go on explaining
     * screens the user never asked for: sit on a screen after tapping, get redirected by a session
     * expiry twenty seconds later, and that redirect reports [NOT_ATTRIBUTABLE_MS] rather than a
     * twenty-second wait nobody experienced.
     *
     * Known limit: a navigation chain that steps again within
     * `ActiveInteractionContext.CONCURRENT_COLLECTOR_GRACE_NANOS` is still timed from the tap, so
     * those steps accumulate rather than partition. Left alone deliberately — at that spacing the
     * user really did wait from the tap.
     *
     * This measures up to the point the navigation framework reports the destination as current.
     * Time the destination then spends composing or loading before anything is drawn is `ttid_ms`,
     * which is not implemented.
     */
    private fun resolveDurationMs(candidate: NavigationTransitionCandidate): Long {
        val intentAtNanos =
            candidate.intentAtNanos?.takeIf { candidate.transitionType == NavigationTransitionType.POP }
                ?: ActiveInteractionContext.lastInteractionStartedAtNanos(candidate.timestampNanos)
                ?: return NOT_ATTRIBUTABLE_MS
        val elapsedNanos = candidate.timestampNanos - intentAtNanos
        if (elapsedNanos < 0 || elapsedNanos > MAX_ATTRIBUTION_NANOS) {
            return NOT_ATTRIBUTABLE_MS
        }
        return elapsedNanos / NANOS_PER_MILLI
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Longest gap between a user action and a destination commit that is still treated as the
         * same navigation. Generous on purpose: a ten-second navigation is a finding, not noise,
         * and must be reported rather than dropped. It only has to be short enough that a
         * navigation with no user action behind it cannot borrow an unrelated tap's timestamp.
         */
        internal const val MAX_ATTRIBUTION_NANOS = 30_000_000_000L

        /**
         * Reported when no trustworthy user-action measurement exists, so the key is present on
         * every `ui.navigation` span rather than missing on some.
         *
         * Three cases reach it: no user action at all behind the navigation (a redirect, a timer, a
         * deep link, a cold-start transition), an action older than [MAX_ATTRIBUTION_NANOS], and a
         * destination that committed before its own action. The last two are cases where an action
         * exists but the measurement derived from it cannot be trusted.
         *
         * **A consumer must not read this as an instant navigation.** Zero means "not measurable",
         * and it shares the column with real measurements, so any average or percentile that
         * includes these rows is pulled toward zero. **The discriminator is the value itself:
         * aggregate over `navigation.duration_ms > 0`.**
         *
         * `navigation.trigger` must *not* be used for this. Timing is deliberately not gated on
         * the parenting or trigger-TTL windows (see [resolveDurationMs]), so a slow navigation
         * carries a real duration under a `unknown` or `programmatic` trigger: the 500 ms window
         * closed before it committed, so the trigger was never upgraded to `user_tap`, and a back
         * press older than the 1 s TTL is named `programmatic`. Filtering to `user_tap`/`back_press`
         * therefore keeps the fast navigations and drops exactly the slow ones this attribute
         * exists to surface. A genuinely instant navigation is not a practical concern — a real
         * tap-driven transition does not commit within the same millisecond as its tap.
         */
        internal const val NOT_ATTRIBUTABLE_MS = 0L

        /** Clears the active navigation context; call from navigation instrumentation [uninstall]. */
        @JvmStatic
        fun clearActiveContext() {
            NavigationActiveContext.clear()
        }
    }
}
