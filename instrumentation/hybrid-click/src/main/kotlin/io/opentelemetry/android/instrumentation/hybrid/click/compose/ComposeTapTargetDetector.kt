/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.opentelemetry.android.instrumentation.hybrid.click.compose

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.Owner
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import io.opentelemetry.android.instrumentation.hybrid.click.shared.LabelResolver
import java.util.LinkedList

/**
 * Resolves Compose tap targets from a hybrid screen's root [View].
 *
 * This detector stays focused on Compose-node traversal and metadata extraction. The conversion to
 * hybrid click model objects is intentionally handled outside this class.
 */
internal class ComposeTapTargetDetector(
    private val composeLayoutNodeUtil: ComposeLayoutNodeUtil = ComposeLayoutNodeUtil(),
) {
    // Single-tap memo for the merged semantics tree. Resolving one tap reads it several times
    // (tappable-id collection, the target's own config, ancestor label ranking); building it is the
    // expensive part. Invalidated at the start of each tap so it never serves a stale tree.
    // Confined to the main thread, like all touch handling.
    private var cachedOwner: Owner? = null
    private var cachedSemanticsNodes: List<SemanticsNode>? = null

    private fun semanticsNodesOf(owner: Owner): List<SemanticsNode> {
        cachedSemanticsNodes?.let { if (cachedOwner === owner) return it }
        val nodes =
            try {
                owner.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            } catch (_: Throwable) {
                emptyList()
            }
        cachedOwner = owner
        cachedSemanticsNodes = nodes
        return nodes
    }
    /**
     * Finds the deepest eligible [LayoutNode] at the provided window coordinates.
     */
    fun findTapTarget(
        rootView: View,
        x: Float,
        y: Float,
    ): LayoutNode? = findLayoutNodeTarget(rootView, x, y)

    /**
     * Resolves a stable display name for telemetry from node semantics/modifier metadata.
     *
     * Editable fields are routed through the same privacy-safe path as [nodeToLabel] so this can
     * never surface typed content, even though only the label currently reaches a span attribute.
     */
    internal fun nodeToName(node: LayoutNode): String =
        try {
            mergedConfigFor(node)?.takeIf { it.contains(SemanticsActions.SetText) }?.let { fieldConfig ->
                return editableFieldLabel(node, fieldConfig)
            }
            getMergedSemanticsLabel(node)
                ?: getNodeName(node)
                ?: getModifierClassName(node)
                ?: nodeId(node)
        } catch (_: Throwable) {
            nodeId(node)
        }

    /**
     * Resolves node coordinates in window space for span attributes.
     */
    internal fun nodeToPosition(node: LayoutNode): Pair<Long, Long> {
        val position = composeLayoutNodeUtil.getLayoutNodePositionInWindow(node)
        return Pair(position?.x?.toLong() ?: 0L, position?.y?.toLong() ?: 0L)
    }

    /**
     * Scans the Android view tree to locate Compose [Owner] roots and delegates node hit-testing.
     */
    private fun findLayoutNodeTarget(
        decorView: View,
        x: Float,
        y: Float,
    ): LayoutNode? {
        val queue = LinkedList<View>()
        queue.addFirst(decorView)

        var target: LayoutNode? = null
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue.add(view.getChildAt(index))
                }
                // Owner is the Compose-internal root; cast succeeds only for AndroidComposeView.
                (view as? Owner)?.let {
                    try {
                        target = findTapTarget(view as Owner, x, y)
                    } catch (_: Throwable) {
                        // Visibility-suppressed internals may throw at runtime.
                    }
                }
            }
        }
        return target
    }

    /**
     * Breadth-first traversal over Compose layout tree to keep the deepest matching node.
     */
    private fun findTapTarget(
        owner: Owner,
        x: Float,
        y: Float,
    ): LayoutNode? {
        // Invalidate the per-tap semantics memo so this tap rebuilds the tree exactly once.
        cachedOwner = null
        cachedSemanticsNodes = null

        // Semantics ids of nodes that are tappable but carry no foundation clickable element — most
        // importantly editable text fields (SetText). In modern Compose, semantics are applied via
        // the Modifier.Node system and are no longer instances of the legacy SemanticsModifier
        // interface, so they can't be detected through getModifierInfo(); the semantics tree is the
        // stable way to find them.
        val tappableSemanticsIds = collectTappableSemanticsIds(owner)

        val queue = LinkedList<LayoutNode>()
        queue.addFirst(owner.root)
        var target: LayoutNode? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isPlaced && hitTest(node, x, y, tappableSemanticsIds)) {
                target = node
            }
            queue.addAll(node.zSortedChildren.asMutableList())
        }
        return target
    }

    /**
     * Collects semantics ids of nodes exposing an `OnClick` or `SetText` action (clickables and
     * editable text fields). Empty if the semantics tree can't be read.
     */
    private fun collectTappableSemanticsIds(owner: Owner): Set<Int> =
        try {
            semanticsNodesOf(owner)
                .filter { node ->
                    node.config.contains(SemanticsActions.OnClick) ||
                        node.config.contains(SemanticsActions.SetText)
                }.map { it.id }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }

    /**
     * Checks coordinate bounds and clickability constraints.
     */
    private fun hitTest(
        node: LayoutNode,
        x: Float,
        y: Float,
        tappableSemanticsIds: Set<Int>,
    ): Boolean {
        val bounded =
            composeLayoutNodeUtil.getLayoutNodeBoundsInWindow(node)?.let { bounds ->
                x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
            } == true

        return bounded && (isValidClickTarget(node) || node.semanticsId in tappableSemanticsIds)
    }

    /**
     * Determines whether node semantics/modifiers represent a tappable element.
     *
     * Besides clickable elements, this also matches editable text fields (nodes exposing
     * [SemanticsActions.SetText]). Tapping a `TextField` is a focus/edit gesture, not an `OnClick`,
     * so without this check field taps would never produce a span. Only the field's label is later
     * used for telemetry — the typed value ([SemanticsProperties.EditableText]) is never read.
     */
    private fun isValidClickTarget(node: LayoutNode): Boolean {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            if (modifier is SemanticsModifier) {
                with(modifier.semanticsConfiguration) {
                    if (contains(SemanticsActions.OnClick) || contains(SemanticsActions.SetText)) {
                        return true
                    }
                }
            } else {
                val className = modifier::class.qualifiedName
                if (
                    className == CLASS_NAME_CLICKABLE_ELEMENT ||
                    className == CLASS_NAME_COMBINED_CLICKABLE_ELEMENT ||
                    className == CLASS_NAME_TOGGLEABLE_ELEMENT
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Produces a user-facing label with fallback resolution strategy.
     *
     * Priority:
     * 1. OnClick label or ContentDescription (from semantics)
     * 2. Text from child Text composables (e.g., button text)
     * 3. Modifier class name (fallback)
     */
    @Suppress("unused") // Used reflectively by ClickEventGenerator
    private fun nodeToLabel(node: LayoutNode): String {
        mergedConfigFor(node)?.takeIf { it.contains(SemanticsActions.SetText) }?.let { fieldConfig ->
            return editableFieldLabel(node, fieldConfig)
        }

        val semanticsLabel = getMergedSemanticsLabel(node) ?: getNodeName(node)
        val childText = extractTextFromChildren(node)
        val className = getModifierClassName(node)
        return LabelResolver.resolve(
            contentDescription = semanticsLabel,
            text = childText,
            className = className,
            fallback = nodeId(node),
        )
    }

    /**
     * Resolves a privacy-safe label for an editable text field.
     *
     * The field's label (its `Text`) is preferred over the merged ContentDescription, which is
     * usually a decorative leading/trailing icon ("Phone"/"Lock"). The typed value
     * ([SemanticsProperties.EditableText]) is **never emitted**: it is read only to exclude any
     * candidate that equals it, and password-flagged fields fall back to a constant rather than risk
     * surfacing entered text. This is a hard guarantee for the apps this ships into (e.g. banking).
     */
    internal fun editableFieldLabel(
        node: LayoutNode,
        fieldConfig: SemanticsConfiguration,
    ): String {
        val typedValue =
            try {
                fieldConfig.getOrNull(SemanticsProperties.EditableText)?.text
            } catch (_: Throwable) {
                null
            }
        val isPassword =
            try {
                fieldConfig.contains(SemanticsProperties.Password)
            } catch (_: Throwable) {
                false
            }
        val hasContent = !typedValue.isNullOrEmpty()

        // A candidate is only usable if it is non-blank and is not the field's current value.
        fun safe(candidate: String?): String? =
            candidate?.takeIf { it.isNotBlank() && it != typedValue }

        // `Text` on a TextField can carry the *displayed* value, which under a VisualTransformation
        // (card / phone / currency masks) differs character-for-character from the raw EditableText
        // and would slip past the equality guard above. So only trust `Text` when the field is empty
        // — then it is the label/placeholder, never user input. contentDescription is the field's
        // own accessibility label or a decorative icon ("Phone"/"Lock"), never the typed value.
        val labelText = if (hasContent) null else fieldConfig.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        val contentDescription = fieldConfig.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

        if (isPassword) {
            // Never let a password field surface anything but its static label.
            return safe(labelText) ?: PASSWORD_FIELD_LABEL
        }

        return safe(labelText)
            ?: safe(contentDescription)
            ?: (getModifierClassName(node) ?: nodeId(node))
    }

    /**
     * Returns the merged [SemanticsConfiguration] for [node] from the semantics tree, or `null` if
     * unavailable. Used to inspect a node's own semantics (e.g. whether it is an editable field).
     */
    private fun mergedConfigFor(node: LayoutNode): SemanticsConfiguration? =
        try {
            val owner = node.owner ?: return null
            semanticsNodesOf(owner)
                .firstOrNull { it.id == node.semanticsId }
                ?.config
        } catch (_: Throwable) {
            null
        }

    /**
     * Extracts text from child Text composables (e.g., "Go to API Test Screen" from Button { Text(...) }).
     * Uses breadth-first traversal to find the nearest text node.
     */
    private fun extractTextFromChildren(node: LayoutNode): String? {
        try {
            val currentNodeText = extractSemanticsText(node)
            if (!currentNodeText.isNullOrBlank()) {
                return currentNodeText
            }

            val queue = LinkedList<LayoutNode>()
            queue.addAll(childNodesOf(node))

            while (queue.isNotEmpty()) {
                val child = queue.removeFirst()
                val text = extractSemanticsText(child)
                if (!text.isNullOrBlank()) {
                    return text
                }

                // Add all children to queue for deeper traversal
                queue.addAll(childNodesOf(child))
            }
        } catch (_: Throwable) {
            // Reflection and Compose internals may throw; fail gracefully
        }
        return null
    }

    private fun childNodesOf(node: LayoutNode): List<LayoutNode> =
        try {
            node.zSortedChildren.asMutableList()
        } catch (_: Throwable) {
            try {
                node.children.toList()
            } catch (_: Throwable) {
                emptyList<LayoutNode>()
            }
        }

    private fun getMergedSemanticsLabel(node: LayoutNode): String? {
        return try {
            val owner = node.owner ?: return null
            val ancestors = collectAncestors(node)
            if (ancestors.isEmpty()) {
                return null
            }

            val rankBySemanticsId =
                ancestors
                    .mapIndexed { index, ancestor -> ancestor.semanticsId to index }
                    .toMap()

            var bestRank = Int.MAX_VALUE
            var bestLabel: String? = null
            for (semanticsNode in semanticsNodesOf(owner)) {
                val rank = rankBySemanticsId[semanticsNode.id]
                if (rank != null) {
                    val semanticsLabel = semanticsLabelFrom(semanticsNode.config)
                    if (!semanticsLabel.isNullOrBlank() && rank < bestRank) {
                        bestLabel = semanticsLabel
                        bestRank = rank
                    }
                }
            }
            bestLabel
        } catch (_: Throwable) {
            null
        }
    }

    private fun collectAncestors(node: LayoutNode): List<LayoutNode> {
        val ancestors = mutableListOf<LayoutNode>()
        var current: LayoutNode? = node
        while (current != null) {
            ancestors.add(current)
            current = current.parent
        }
        return ancestors
    }

    private fun semanticsLabelFrom(configuration: SemanticsConfiguration): String? {
        val contentDescription =
            configuration.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        if (!contentDescription.isNullOrBlank()) {
            return contentDescription
        }

        val text = configuration.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        if (!text.isNullOrBlank()) {
            return text
        }

        return null
    }

    private fun extractSemanticsText(node: LayoutNode): String? {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            if (modifier is SemanticsModifier) {
                with(modifier.semanticsConfiguration) {
                    val textList = getOrNull(SemanticsProperties.Text)
                    if (textList != null && textList.isNotEmpty()) {
                        val text = textList[0].text
                        if (text.isNotBlank()) {
                            return text
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Extracts the modifier class name as a fallback label.
     */
    private fun getModifierClassName(node: LayoutNode): String? {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            if (modifier !is SemanticsModifier) {
                return modifier::class.qualifiedName
            }
        }
        return null
    }

    // Fallback semantics precedence only: ContentDescription
    private fun getNodeName(node: LayoutNode): String? {
        for (info in node.getModifierInfo()) {
            val modifier = info.modifier
            if (modifier is SemanticsModifier) {
                with(modifier.semanticsConfiguration) {
                    val contentDescriptionList =
                        getOrNull(SemanticsProperties.ContentDescription)
                    if (contentDescriptionList != null) {
                        val contentDescription = contentDescriptionList.getOrNull(0)
                        if (contentDescription != null) {
                            return contentDescription
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Returns a stable fallback node identifier used in telemetry.
     */
    private fun nodeId(node: LayoutNode): String = node.hashCode().toString()

    companion object {
        private const val CLASS_NAME_CLICKABLE_ELEMENT =
            "androidx.compose.foundation.ClickableElement"
        private const val CLASS_NAME_COMBINED_CLICKABLE_ELEMENT =
            "androidx.compose.foundation.CombinedClickableElement"
        private const val CLASS_NAME_TOGGLEABLE_ELEMENT =
            "androidx.compose.foundation.selection.ToggleableElement"

        /** Safe placeholder used when a password field has no usable non-value label. */
        private const val PASSWORD_FIELD_LABEL = "password field"
    }
}
