package com.kgs.notes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.UnreadableReason
import com.kgs.notes.engine.UnreadableRecord
import com.kgs.notes.engine.UnreadableRecordKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ReadOnlyAndRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val server = SourceId("server")
    private val sharedNote = Note(
        id = NoteId("shared"), sourceId = server, syncState = NoteSyncState.SYNCED, title = "Shared plan",
        markdown = "# Shared plan\n\nRead only", category = "Team", favorite = false, state = NoteState.ACTIVE,
        createdAt = Instant.parse("2026-08-22T08:00:00Z"), updatedAt = Instant.parse("2026-08-22T09:00:00Z"),
        readOnly = true,
    )

    @Test
    fun readOnlyNoteExplainsItselfAndOffersOnlyPermittedActions() {
        var copies = 0
        var favorites = 0
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    NotesUiState(selectedNote = sharedNote, sources = listOf(
                        SourceChoice(LocalSourceId, "Local Source", true),
                        SourceChoice(server, "Server", true, supportsAttachments = false),
                    )),
                    NotesActions(onCopyToLocal = { copies++ }, onToggleFavorite = { favorites++ }),
                )
            }
        }
        compose.onNodeWithTag("read-only-banner").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose Category: Team").assertDoesNotExist()
        compose.onNodeWithTag("read-only-copy").performClick()
        assertEquals(1, copies)
        compose.onNodeWithContentDescription("More note actions").performClick()
        compose.onNodeWithTag("dropdown-item-Delete").assertDoesNotExist()
        compose.onNodeWithTag("dropdown-item-Favourite").performClick()
        assertEquals(1, favorites)
    }

    @Test
    fun saveFailureOffersRetryCopyAndExplicitDiscard() {
        var retries = 0
        var discards = 0
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    NotesUiState(selectedNote = sharedNote.copy(readOnly = false, sourceId = LocalSourceId), saveFailed = true),
                    NotesActions(onRetrySave = { retries++ }, onDiscardUnsavedChanges = { discards++ }),
                )
            }
        }
        compose.onNodeWithTag("save-failure-banner").assertIsDisplayed()
        compose.onNodeWithTag("retry-save").performClick()
        compose.onNodeWithTag("discard-unsaved-changes").performClick()
        assertEquals(1, retries)
        assertEquals(1, discards)
    }

    @Test
    fun unreadableRecordsAreDiscoverableRescuableAndOnlyRemovedAfterConfirmation() {
        val record = UnreadableRecord(
            id = "record-1", kind = UnreadableRecordKind.LOCAL_NOTE, reason = UnreadableReason.MALFORMED_METADATA,
            noteId = NoteId("broken"), sourceId = LocalSourceId, title = "Damaged trip", detail = "state=INVALID_STATE",
            files = listOf("/data/local-source/.kgs/broken.properties"), hasContent = true,
        )
        val rescued = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val state = mutableStateOf(NotesUiState(destination = LibraryDestination.SETTINGS, unreadableRecords = listOf(record)))
        compose.setContent {
            KgsNotesTheme {
                NotesSettingsScreen(state.value, NotesActions(
                    onRescueUnreadableRecord = { rescued += it },
                    onDiscardUnreadableRecord = { discarded += it },
                ))
            }
        }
        compose.onNodeWithTag("settings-open-RECOVERY").performScrollTo().performClick()
        compose.onNodeWithText("Damaged trip").assertIsDisplayed()
        compose.onNodeWithText("Its details are damaged").assertIsDisplayed()
        compose.onNodeWithTag("recovery-rescue-record-1").performClick()
        assertEquals(listOf("record-1"), rescued)
        compose.onNodeWithTag("recovery-discard-record-1").performScrollTo().performClick()
        assertEquals(emptyList<String>(), discarded)
        compose.onNodeWithTag("recovery-discard-confirm").performClick()
        assertEquals(listOf("record-1"), discarded)
    }
}
