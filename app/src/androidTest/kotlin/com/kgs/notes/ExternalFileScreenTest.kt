package com.kgs.notes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExternalFileScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openingAndEditingDoNotImportUntilASourceIsChosen() {
        val file = mutableStateOf(ExternalFileUiState(name = "Shopping.txt", text = "Milk", writable = true, mode = EditorMode.SOURCE))
        var copiedTo: SourceId? = null
        compose.setContent {
            KgsNotesTheme {
                ExternalFileScreen(file.value, listOf(SourceChoice(LocalSourceId, "Local Source", true)),
                    onTextChange = { file.value = file.value.copy(text = it, dirty = true) },
                    onModeChange = {}, onCopy = { copiedTo = it }, onClose = {}, onRetry = {}, onDiscard = {})
            }
        }
        compose.onNodeWithTag("source-editor").assertTextContains("Milk")
        assertEquals(null, copiedTo)
        compose.onNodeWithTag("source-editor").performClick().performTextInput(" and eggs")
        assertEquals(null, copiedTo)
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithText("The original file stays in its current location.").assertIsDisplayed()
        compose.onNodeWithTag("single-choice-Local Source").performClick()
        assertEquals(LocalSourceId, copiedTo)
    }

    @Test
    fun readOnlyFileExplainsItsAccessAndStillOffersAWritableSource() {
        val server = SourceId("server")
        var copiedTo: SourceId? = null
        compose.setContent {
            KgsNotesTheme {
                ExternalFileScreen(ExternalFileUiState(name = "Readme.md", text = "Original", mode = EditorMode.SOURCE),
                    listOf(SourceChoice(LocalSourceId, "Local Source", true), SourceChoice(server, "Server", true)),
                    onTextChange = {}, onModeChange = {}, onCopy = { copiedTo = it }, onClose = {}, onRetry = {}, onDiscard = {})
            }
        }
        compose.onNodeWithText("Read-only file").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Server").performClick()
        assertEquals(server, copiedTo)
    }

    @Test
    fun unavailableAndReadOnlySourcesAreNotCopyDestinations() {
        compose.setContent {
            KgsNotesTheme {
                ExternalFileScreen(ExternalFileUiState(name = "Note.txt", text = "Hello"),
                    listOf(SourceChoice(LocalSourceId, "Local Source", true),
                        SourceChoice(SourceId("disconnected"), "Disconnected", true, connected = false),
                        SourceChoice(SourceId("read-only"), "Read-only Source", true, writable = false)),
                    onTextChange = {}, onModeChange = {}, onCopy = {}, onClose = {}, onRetry = {}, onDiscard = {})
            }
        }
        compose.onNodeWithContentDescription("Save to KGS Notes").performClick()
        compose.onNodeWithTag("single-choice-Local Source").assertIsDisplayed()
        compose.onNodeWithTag("single-choice-Disconnected").assertDoesNotExist()
        compose.onNodeWithTag("single-choice-Read-only Source").assertDoesNotExist()
    }
}
