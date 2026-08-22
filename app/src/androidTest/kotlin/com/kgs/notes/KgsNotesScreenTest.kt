package com.kgs.notes

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.engine.LocalNotesEngine
import com.kgs.notes.engine.LibrarySnapshot
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.SourceId
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

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

    @Test
    fun editorHeaderUsesCompactStatusCategoryAndRoundedActionMenus() {
        var category = ""
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        selectedNote = sampleNote(category = "Trips/Forest"),
                        categories = listOf("Trips/Forest", "Work"),
                    ),
                    actions = NotesActions(onCategoryChange = { category = it }),
                )
            }
        }

        compose.onNodeWithContentDescription("Saved locally").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose Category: Trips/Forest").performClick()
        compose.onNodeWithText("No Category").assertIsDisplayed()
        compose.onNodeWithText("Trips/Forest").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("New Category").assertIsDisplayed()
        compose.onNodeWithText("Work").performClick()
        assertEquals("Work", category)

        compose.onNodeWithContentDescription("More note actions").performClick()
        compose.onNodeWithText("Favourite").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun saveStatusOpensSourceDestinationDialog() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithContentDescription("Saved locally").performClick()
        compose.onNodeWithText("Save Note to").assertIsDisplayed()
        compose.onNodeWithText("Local Source").assertIsDisplayed()
    }

    @Test
    fun editorToolbarKeepsModeListsAndHistoryActionsTogether() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to Source Mode").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lists").performClick()
        compose.onNodeWithText("Bulleted list").assertIsDisplayed()
        compose.onNodeWithText("Checklist").assertIsDisplayed()
        compose.onNodeWithText("Numbered list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Redo").assertIsDisplayed()
    }

    @Test
    fun libraryUsesCompactFiltersSortAndSourceDrawer() {
        val serverId = SourceId("home-cloud")
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        notes = listOf(sampleSummary(serverId, NoteSyncState.NEEDS_ATTENTION)),
                        pendingSyncCount = 2,
                        sources = listOf(
                            SourceChoice(LocalSourceId, "Local Source", true),
                            SourceChoice(serverId, "Home Cloud", true),
                        ),
                    ),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithContentDescription("All notes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favorite notes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Local notes, 2 awaiting sync").assertIsDisplayed()
        compose.onNodeWithContentDescription("Server notes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Awaiting sync").assertIsDisplayed()
        compose.onNodeWithContentDescription("Descending").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort notes").performClick()
        compose.onNodeWithText("Last edited").assertIsDisplayed()
        compose.onNodeWithText("Last created").assertIsDisplayed()
        compose.onNodeWithText("Alphabetical").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Sources").assertIsDisplayed()
        compose.onNodeWithText("Local Source").assertIsDisplayed()
        compose.onNodeWithText("Home Cloud").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun noteCardPressFeedbackStaysInsideItsRoundedShape() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE))),
                    actions = NotesActions(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val card = compose.onNodeWithTag("note-card-waiting-for-cloud")
        val before = card.captureToImage().toPixelMap()[4, 4]

        card.performTouchInput { down(Offset(4f, 4f)) }
        compose.mainClock.advanceTimeBy(120)
        val pressed = card.captureToImage().toPixelMap()[4, 4]
        card.performTouchInput { up() }

        assertEquals("Press feedback escaped the rounded card corner", before, pressed)
    }

    @Test
    fun settingsHasAnExplicitBackToNotesAction() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(destination = LibraryDestination.SETTINGS),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithContentDescription("Back to Notes").assertIsDisplayed()
    }

    @Test
    fun closingEditorComposesOneOutgoingEditorAndOneIncomingLibrary() {
        val selected = mutableStateOf<Note?>(sampleNote())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = selected.value),
                    actions = NotesActions(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnIdle { selected.value = null }
        compose.mainClock.advanceTimeBy(16)

        compose.onAllNodesWithTag("library-pane").assertCountEquals(1)
        compose.onAllNodesWithTag("note-editor").assertCountEquals(1)
    }

    @Test
    fun editorStatusDistinguishesSavingSyncingAndSynced() {
        val serverId = SourceId("home-cloud")
        val uiState = mutableStateOf(
            NotesUiState(
                selectedNote = sampleNote(
                    sourceId = serverId,
                    syncState = NoteSyncState.SAVED_LOCALLY,
                ),
                saving = true,
            ),
        )
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(state = uiState.value, actions = NotesActions())
            }
        }

        compose.onNodeWithContentDescription("Saving").assertIsDisplayed()
        compose.runOnIdle {
            uiState.value = uiState.value.copy(
                saving = false,
                selectedNote = uiState.value.selectedNote?.copy(syncState = NoteSyncState.SYNCING),
            )
        }
        compose.onNodeWithContentDescription("Saved locally, syncing").assertIsDisplayed()
        compose.runOnIdle {
            uiState.value = uiState.value.copy(
                selectedNote = uiState.value.selectedNote?.copy(syncState = NoteSyncState.SYNCED),
            )
        }
        compose.onNodeWithContentDescription("Synced").assertIsDisplayed()
    }

    @Test
    fun localAndServerFiltersFollowSourceSynchronizationState() {
        val serverId = SourceId("home-cloud")
        val engine = ControlledNotesEngine(
            LibrarySnapshot(
                active = listOf(
                    sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Local journal"),
                    sampleSummary(serverId, NoteSyncState.NEEDS_ATTENTION, "Waiting upload"),
                    sampleSummary(serverId, NoteSyncState.SYNCED, "Cloud journal"),
                ),
            ),
        )
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = NotesViewModel(application, engine)
        compose.setContent {
            KgsNotesTheme { KgsNotesApp(viewModel) }
        }

        compose.onNodeWithContentDescription("Local notes, 1 awaiting sync").performClick()
        compose.onNodeWithText("Local journal").assertIsDisplayed()
        compose.onNodeWithText("Waiting upload").assertIsDisplayed()
        compose.onAllNodesWithText("Cloud journal").assertCountEquals(0)

        compose.onNodeWithContentDescription("Server notes").performClick()
        compose.onNodeWithText("Cloud journal").assertIsDisplayed()
        compose.onAllNodesWithText("Local journal").assertCountEquals(0)
        compose.onAllNodesWithText("Waiting upload").assertCountEquals(0)
    }

    private fun sampleNote(
        category: String = "",
        sourceId: SourceId = LocalSourceId,
        syncState: NoteSyncState = NoteSyncState.LOCAL_SOURCE,
    ) = Note(
        id = NoteId("note-1"),
        sourceId = sourceId,
        syncState = syncState,
        title = "Field notes",
        markdown = "# Field notes\n\nClouds over the ridge",
        category = category,
        favorite = false,
        state = NoteState.ACTIVE,
        createdAt = Instant.parse("2026-08-22T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-22T09:00:00Z"),
    )

    private fun sampleSummary(
        sourceId: SourceId,
        syncState: NoteSyncState,
        title: String = "Waiting for cloud",
    ) = NoteSummary(
        id = NoteId(title.lowercase().replace(' ', '-')),
        sourceId = sourceId,
        syncState = syncState,
        title = title,
        snippet = "Saved safely on this device",
        category = "",
        favorite = false,
        createdAt = Instant.parse("2026-08-22T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-22T09:00:00Z"),
    )

    private class ControlledNotesEngine(snapshot: LibrarySnapshot) : NotesEngine {
        override val library = MutableStateFlow(snapshot)

        override suspend fun createDraft(category: String) = error("Not used")
        override suspend fun note(id: NoteId): Note? = null
        override suspend fun updateContent(id: NoteId, markdown: String) = Unit
        override suspend fun rename(id: NoteId, title: String) = Unit
        override suspend fun setCategory(id: NoteId, category: String) = Unit
        override suspend fun toggleFavorite(id: NoteId) = Unit
        override suspend fun moveToTrash(id: NoteId) = Unit
        override suspend fun restore(id: NoteId) = Unit
        override suspend fun deletePermanently(id: NoteId) = Unit
        override suspend fun close(id: NoteId) = Unit
    }
}
