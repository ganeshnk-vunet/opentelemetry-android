/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.opentelemetry.android.instrumentation.hybrid.click.compose

import androidx.compose.runtime.collection.mutableVectorOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ComposeTapTargetDetectorTest {
    lateinit var detector: ComposeTapTargetDetector

    @MockK
    lateinit var composeLayoutNodeUtil: ComposeLayoutNodeUtil

    @MockK
    lateinit var semanticsModifier: SemanticsModifier

    @MockK
    lateinit var modifier: Modifier

    @MockK
    lateinit var semanticsConfiguration: SemanticsConfiguration

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        detector = ComposeTapTargetDetector(composeLayoutNodeUtil)
    }

    @Test
    fun `name falls back when only onClick label exists`() {
        val name = detector.nodeToName(createMockLayoutNode(clickable = true))
        assertThat(name).isNotBlank()
    }

    @Test
    fun `name from content description`() {
        val name =
            detector.nodeToName(
                createMockLayoutNode(clickable = true, useDescription = true),
            )
        assertThat(name).isEqualTo("clickMe")
    }

    @Test
    fun `name falls back to hashCode on exception`() {
        val mockNode = mockkClass(LayoutNode::class)
        every { mockNode.getModifierInfo() } throws RuntimeException("test")

        val name = detector.nodeToName(mockNode)
        assertThat(name).isNotBlank()
    }

    @Test
    fun `name falls back to hashCode when no modifiers`() {
        val mockNode = mockkClass(LayoutNode::class)
        every { mockNode.getModifierInfo() } returns listOf()

        val name = detector.nodeToName(mockNode)
        assertThat(name).isNotBlank()
    }

    @Test
    fun `editable field uses its label when empty`() {
        val label =
            detector.editableFieldLabel(
                fieldNode(),
                fieldConfig(editable = "", text = "Mobile Number"),
            )
        assertThat(label).isEqualTo("Mobile Number")
    }

    @Test
    fun `editable field with masked content never emits the displayed value`() {
        val raw = "4111111111111111"
        val masked = "4111 1111 1111 1111"
        val label =
            detector.editableFieldLabel(
                fieldNode(),
                fieldConfig(editable = raw, text = masked),
            )
        assertThat(label).isNotEqualTo(masked)
        assertThat(label).isNotEqualTo(raw)
    }

    @Test
    fun `editable field excludes a content description equal to the typed value`() {
        val value = "john@example.com"
        val label =
            detector.editableFieldLabel(
                fieldNode(),
                fieldConfig(editable = value, contentDescription = value),
            )
        assertThat(label).isNotEqualTo(value)
    }

    @Test
    fun `empty password field uses its label`() {
        val label =
            detector.editableFieldLabel(
                fieldNode(),
                fieldConfig(editable = "", text = "Password", password = true),
            )
        assertThat(label).isEqualTo("Password")
    }

    @Test
    fun `password field with content falls back to a constant`() {
        val secret = "s3cr3t"
        val label =
            detector.editableFieldLabel(
                fieldNode(),
                fieldConfig(editable = secret, text = "••••••", contentDescription = "Lock", password = true),
            )
        assertThat(label).isEqualTo("password field")
        assertThat(label).isNotEqualTo(secret)
    }

    @Test
    fun `widget type from semantics role`() {
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.Button))).isEqualTo("button")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.Switch))).isEqualTo("switch")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.Checkbox))).isEqualTo("checkbox")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.RadioButton))).isEqualTo("radio")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.Tab))).isEqualTo("tab")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.Image))).isEqualTo("image")
        assertThat(detector.widgetTypeOf(typeConfig(role = Role.DropdownList))).isEqualTo("dropdown")
    }

    @Test
    fun `widget type falls back to actions when no role`() {
        assertThat(detector.widgetTypeOf(typeConfig(role = null, setText = true))).isEqualTo("text_field")
        assertThat(detector.widgetTypeOf(typeConfig(role = null, onClick = true))).isEqualTo("button")
        assertThat(detector.widgetTypeOf(typeConfig(role = null))).isEqualTo("unknown")
        assertThat(detector.widgetTypeOf(null)).isEqualTo("unknown")
    }

    private fun typeConfig(
        role: Role?,
        setText: Boolean = false,
        onClick: Boolean = false,
    ): SemanticsConfiguration {
        val config = SemanticsConfiguration()
        if (role != null) {
            config[SemanticsProperties.Role] = role
        }
        if (setText) {
            config[SemanticsActions.SetText] = AccessibilityAction<(AnnotatedString) -> Boolean>("setText") { true }
        }
        if (onClick) {
            config[SemanticsActions.OnClick] = AccessibilityAction<() -> Boolean>("onClick") { true }
        }
        return config
    }

    /** A LayoutNode whose only role is the class-name/hashCode fallback path. */
    private fun fieldNode(): LayoutNode =
        mockkClass(LayoutNode::class).also { every { it.getModifierInfo() } returns listOf() }

    private fun fieldConfig(
        editable: String,
        text: String? = null,
        contentDescription: String? = null,
        password: Boolean = false,
    ): SemanticsConfiguration {
        every { semanticsConfiguration.getOrNull(SemanticsProperties.EditableText) } returns AnnotatedString(editable)
        every { semanticsConfiguration.contains(SemanticsProperties.Password) } returns password
        every { semanticsConfiguration.getOrNull(SemanticsProperties.Text) } returns
            text?.let { listOf(AnnotatedString(it)) }
        every { semanticsConfiguration.getOrNull(SemanticsProperties.ContentDescription) } returns
            contentDescription?.let { listOf(it) }
        return semanticsConfiguration
    }

    private fun createMockLayoutNode(
        targetX: Float = 0f,
        targetY: Float = 0f,
        hitOffset: IntArray = intArrayOf(10, 20),
        hit: Boolean = false,
        clickable: Boolean = false,
        useDescription: Boolean = false,
    ): LayoutNode {
        val mockNode = mockkClass(LayoutNode::class)
        every { mockNode.isPlaced } returns true

        val bounds =
            if (hit) {
                Rect(
                    left = targetX - hitOffset[0],
                    right = targetX + hitOffset[0],
                    top = targetY - hitOffset[1],
                    bottom = targetY + hitOffset[1],
                )
            } else {
                Rect(
                    left = targetX + hitOffset[0],
                    right = targetX + hitOffset[0],
                    top = targetY + hitOffset[1],
                    bottom = targetY + hitOffset[1],
                )
            }

        val mockModifierInfo = mockkClass(ModifierInfo::class)
        every { mockNode.getModifierInfo() } returns listOf(mockModifierInfo)
        if (clickable) {
            every { mockModifierInfo.modifier } returns semanticsModifier
            every { semanticsModifier.semanticsConfiguration } returns semanticsConfiguration
            every { semanticsConfiguration.contains(SemanticsActions.OnClick) } returns true

            if (useDescription) {
                every { semanticsConfiguration.getOrNull(SemanticsActions.OnClick) } returns null
                every { semanticsConfiguration.getOrNull(SemanticsProperties.ContentDescription) } returns
                    listOf("clickMe")
            } else {
                every { semanticsConfiguration.getOrNull(SemanticsActions.OnClick) } returns
                    AccessibilityAction<() -> Boolean>("click") { true }
            }
        } else {
            every { mockModifierInfo.modifier } returns modifier
        }

        every { mockNode.zSortedChildren } returns mutableVectorOf()
        every { composeLayoutNodeUtil.getLayoutNodeBoundsInWindow(mockNode) } returns bounds
        every { composeLayoutNodeUtil.getLayoutNodePositionInWindow(mockNode) } returns
            Offset(x = bounds.left, y = bounds.top)

        return mockNode
    }
}
