package com.kgs.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import com.kgs.notes.design.KgsNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BottomSheetRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sortSheetFitsItsOptionsAndTouchesBothSidesAndTheBottom() {
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(), NotesActions()) } }
        compose.onNodeWithContentDescription("Sort notes").performClick()
        val frame = compose.onNodeWithTag("kgs-sheet-frame").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("sort-options-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.left, sheet.left, 1f)
        assertEquals(frame.right, sheet.right, 1f)
        assertEquals(frame.bottom, sheet.bottom, 1f)
        assertTrue("Short sheets should fit content rather than fill the screen", sheet.height < frame.height * .75f)
        for (option in listOf("Last edited", "Last created", "Alphabetical")) compose.onNodeWithText(option).assertIsDisplayed()
        compose.onNodeWithTag("sort-options-menu").performTouchInput { click(Offset(width - 2f, height * .5f)) }
        compose.onNodeWithTag("sort-options-menu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss sheet").performClick()
        compose.onNodeWithTag("sort-options-menu").assertDoesNotExist()
    }

    @Test
    fun pullingTheHandleABitReturnsTheSheetAndAFlickDismissesIt() {
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(), NotesActions()) } }
        compose.onNodeWithContentDescription("Sort notes").performClick()
        compose.onNodeWithTag("kgs-sheet-handle").performTouchInput {
            swipe(center, center + Offset(0f, 12f), durationMillis = 600)
        }
        compose.onNodeWithTag("sort-options-menu").assertIsDisplayed()
        val frame = compose.onNodeWithTag("kgs-sheet-frame").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("kgs-sheet-handle").performTouchInput {
            swipe(center, center + Offset(0f, frame.height * .4f), durationMillis = 130)
        }
        compose.onNodeWithTag("sort-options-menu").assertDoesNotExist()
    }

    @Test
    fun aSheetKeepsItsContentDuringItsClosingAnimation() {
        val open = mutableStateOf(true)
        compose.setContent {
            KgsNotesTheme {
                com.kgs.notes.design.KgsBottomSheet(expanded = open.value, onDismissRequest = { open.value = false }) {
                    androidx.compose.material3.Text("Retained sheet fixture")
                }
            }
        }
        compose.onNodeWithText("Retained sheet fixture").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Retained sheet fixture").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Retained sheet fixture").assertDoesNotExist()
    }
    @Test
    fun shrinkingSheetKeepsItsSurfaceFilledToTheBottomThroughoutTheResize() {
        val contentHeight = mutableStateOf(300.dp)
        compose.setContent {
            KgsNotesTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF114488)))
                com.kgs.notes.design.KgsBottomSheet(true, {}, modal = false, containerColor = Color.White) {
                    Box(Modifier.fillMaxWidth().height(contentHeight.value))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { contentHeight.value = 70.dp }
        repeat(5) {
            compose.mainClock.advanceTimeBy(48)
            val body = compose.onNodeWithTag("kgs-sheet-body")
            val frame = compose.onNodeWithTag("kgs-sheet-frame").fetchSemanticsNode().boundsInRoot
            assertEquals("The animated sheet must remain flush to the bottom", frame.bottom, body.fetchSemanticsNode().boundsInRoot.bottom, 1f)
            val pixels = body.captureToImage().toPixelMap()
            val bottom = pixels[pixels.width / 2, pixels.height - 2]
            assertTrue("Shrinking content must not expose the background inside the sheet", bottom.red > .95f && bottom.green > .95f && bottom.blue > .95f)
        }
    }

    @Test
    fun sheetShadowFollowsTheRoundedCornersAndFadesWithTheSheet() {
        val open = mutableStateOf(false)
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            KgsNotesTheme {
                Box(Modifier.fillMaxSize().background(Color.White))
                com.kgs.notes.design.KgsBottomSheet(open.value, { open.value = false }, modal = false, containerColor = Color.White) {
                    Box(Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open.value = true }
        compose.mainClock.advanceTimeBy(96)
        fun shadowShade(corner: Boolean = false): Float {
            val bounds = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val x = if (corner) bounds.left + 4f * density else bounds.center.x
            return 1f - pixels[x.toInt().coerceIn(0, pixels.width - 1), (bounds.top - 5f * density).toInt().coerceIn(0, pixels.height - 1)].red
        }
        val appearing = shadowShade()
        compose.mainClock.advanceTimeBy(400)
        val shown = shadowShade()
        assertTrue("The shadow must fade in with the moving sheet", appearing > .001f && appearing < shown)
        assertTrue("The shadow must follow the top corners, rather than a straight rectangle", shadowShade(corner = true) < shown - .01f)
        compose.runOnIdle { open.value = false }
        compose.mainClock.advanceTimeBy(96)
        assertTrue("The shadow must fade out during dismissal", shadowShade() < shown)
    }

    @Test
    fun changingContentWithinAnOpenActionMenuResizesSmoothly() {
        val height = mutableStateOf(300.dp)
        compose.setContent {
            KgsNotesTheme {
                com.kgs.notes.design.KgsBottomSheet(true, {}, modal = false, animateSizeChanges = false) {
                    androidx.compose.runtime.CompositionLocalProvider(com.kgs.notes.design.LocalKgsInlineSheet provides true) {
                        com.kgs.notes.design.KgsBottomSheet(true, {}) {
                            Box(Modifier.fillMaxWidth().height(height.value))
                        }
                    }
                }
            }
        }
        val initial = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { height.value = 70.dp }
        compose.mainClock.advanceTimeBy(80)
        val changing = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(400)
        val final = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Open menus must move smoothly between their content heights", changing > final + 2f && changing < initial - 2f)
    }

}
