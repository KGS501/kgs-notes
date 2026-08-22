package com.kgs.notes

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.engine.LocalNotesEngine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

class KgsNotesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyLibraryOffersANewNoteAction() {
        var clicked = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(),
                    actions = NotesActions(onCreate = { clicked = true }),
                )
            }
        }

        compose.onNodeWithTag("empty-library").assertIsDisplayed()
        compose.onNodeWithContentDescription("New note").performClick()
        assertTrue(clicked)
    }

    @Test
    fun viewModelImmediatelyShowsNotesLoadedAtColdStart() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val root = application.cacheDir.resolve("cold-start-${System.nanoTime()}")
            val engine = LocalNotesEngine.open(root.toPath())
            val id = engine.createDraft()
            engine.updateContent(id, "# Loaded note\n\nStill here after restart")
            engine.close(id)

            val viewModel = NotesViewModel(application, engine)

            assertTrue(viewModel.state.value.notes.any { it.title == "Loaded note" })
            root.deleteRecursively()
        }
    }
}
