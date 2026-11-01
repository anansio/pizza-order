package io.github.kaerbr.pizzaorder.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager

/**
 * A tap outside the focused text field ends its focus and closes the keyboard; Compose on its own
 * keeps the focus until another field takes it. Goes on the app's root, dialogs are their own windows.
 */
internal fun Modifier.clearFocusOnOutsideTap(): Modifier = this then ClearFocusOnOutsideTapElement

private data object ClearFocusOnOutsideTapElement : ModifierNodeElement<ClearFocusOnOutsideTapNode>() {
    override fun create() = ClearFocusOnOutsideTapNode()
    override fun update(node: ClearFocusOnOutsideTapNode) = Unit
}

private class ClearFocusOnOutsideTapNode : DelegatingNode(), CompositionLocalConsumerModifierNode {
    // Never takes focus itself, it's only here to ask where the focused child sits.
    private val focusTarget = delegate(FocusTargetModifierNode(focusability = Focusability.Never))

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    // Initial pass: chips and buttons consume their taps, which also count as outside.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A scroll cancels this, so dragging the form keeps the field active.
                    waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                    val field = focusTarget.getFocusedRect() ?: return@awaitEachGesture
                    if (down.position !in field) currentValueOf(LocalFocusManager).clearFocus()
                }
            },
        )
    }
}
