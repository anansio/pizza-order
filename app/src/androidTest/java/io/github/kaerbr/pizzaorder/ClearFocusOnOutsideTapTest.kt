package io.github.kaerbr.pizzaorder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.kaerbr.pizzaorder.ui.clearFocusOnOutsideTap
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ClearFocusOnOutsideTapTest {
    @get:Rule
    val rule = createComposeRule()

    private var chipClicks = 0

    @Before
    fun setUp() {
        rule.setContent {
            Column(Modifier.fillMaxSize().clearFocusOnOutsideTap()) {
                BasicTextField("", {}, Modifier.fillMaxWidth().height(48.dp).testTag("field"))
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("chip").clickable { chipClicks++ })
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("blank"))
            }
        }
    }

    @Test
    fun tapOnField_keepsFocus() {
        rule.onNodeWithTag("field").performClick().assertIsFocused()
        rule.onNodeWithTag("field").performClick().assertIsFocused()
    }

    @Test
    fun tapOnBlankArea_clearsFocus() {
        rule.onNodeWithTag("field").performClick().assertIsFocused()
        rule.onNodeWithTag("blank").performClick()
        rule.onNodeWithTag("field").assertIsNotFocused()
    }

    @Test
    fun tapOnClickable_clearsFocusAndStillClicks() {
        rule.onNodeWithTag("field").performClick().assertIsFocused()
        rule.onNodeWithTag("chip").performClick()
        rule.onNodeWithTag("field").assertIsNotFocused()
        rule.runOnIdle { assertEquals(1, chipClicks) }
    }
}
