package com.kgs.notes

import android.graphics.Rect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LibraryImprovementsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleKeepsEmptyTextAndTrailingSpaceWhileCanonicalMetadataChanges() {
        val selected = mutableStateOf(note())
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(selectedNote = selected.value, editorMode = EditorMode.SOURCE),
            NotesActions(onRename = { title -> selected.value = selected.value.copy(title = title.trim().ifBlank { "New note" }) }),
        ) } }
        val title = compose.onNodeWithTag("editor-title")
        title.performClick().performTextClearance()
        compose.waitForIdle()
        title.assertTextEquals("")
        title.performTextInput("First ")
        compose.waitForIdle()
        title.assertTextEquals("First ")
        title.performTextInput("word")
        title.assertTextEquals("First word")
    }

    @Test fun reversingDateSortReversesSectionsForBothEditedAndCreatedSort() {
        val sort = mutableStateOf(LibrarySort.LAST_EDITED)
        val ascending = mutableStateOf(false)
        val today = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        val old = today.minusSeconds(4 * 86400)
        val recentNote = summary("Recent").copy(updatedAt = today, createdAt = today)
        val oldNote = summary("Older").copy(updatedAt = old, createdAt = old)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(
            notes = if (ascending.value) listOf(oldNote, recentNote) else listOf(recentNote, oldNote),
            groupByDate = true, sort = sort.value, ascending = ascending.value,
        ), NotesActions()) } }
        fun assertFirst(heading: String) {
            compose.onNodeWithTag("notes-list").performScrollToIndex(0)
            val first = compose.onNodeWithTag("notes-section-$heading").fetchSemanticsNode().boundsInRoot
            assertTrue(first.height > 0)
            val card = compose.onNodeWithTag("note-card-${if (heading == "Today") recentNote.id.value else oldNote.id.value}").fetchSemanticsNode().boundsInRoot
            assertTrue(first.bottom <= card.top + 1f)
        }
        for (mode in listOf(LibrarySort.LAST_EDITED, LibrarySort.LAST_CREATED)) {
            compose.runOnIdle { sort.value = mode; ascending.value = false }
            assertFirst("Today")
            compose.runOnIdle { ascending.value = true }
            assertFirst("Last 7 Days")
        }
    }

    @Test fun alphabeticalGroupingUsesLettersAndReversesTheirOrder() {
        val ascending = mutableStateOf(true)
        val a = summary("Alpine")
        val z = summary("Zebra")
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(
            notes = if (ascending.value) listOf(a, z) else listOf(z, a),
            sort = LibrarySort.ALPHABETICAL, groupByDate = true, ascending = ascending.value,
        ), NotesActions()) } }
        compose.onNodeWithTag("notes-section-A").assertIsDisplayed()
        compose.onNodeWithTag("notes-section-Today").assertDoesNotExist()
        compose.runOnIdle { ascending.value = false }
        compose.onNodeWithTag("notes-list").performScrollToIndex(0)
        compose.onNodeWithTag("notes-section-Z").assertIsDisplayed()
        val heading = compose.onNodeWithTag("notes-section-Z").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("note-card-${z.id.value}").fetchSemanticsNode().boundsInRoot
        assertTrue(heading.bottom <= card.top + 1f)
    }

    @Test fun categoryChooserLeavesManagementToSettings() {
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(selectedNote = note().copy(category = "Work"),
            categories = listOf("Work"), editorMode = EditorMode.SOURCE), NotesActions()) } }
        compose.onNodeWithContentDescription("Choose Category: Work").performClick()
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onNodeWithText("Manage Categories").assertDoesNotExist()
    }

    @Test fun categorySettingsCreateRenameAndRemoveKeepTheirSourceIdentity() {
        val state = mutableStateOf(NotesUiState(destination = LibraryDestination.SETTINGS,
            managedCategories = listOf(ManagedCategory(LocalSourceId, "Work", 1))))
        var created: Pair<SourceId, String>? = null
        var renamed: Triple<SourceId, String, String>? = null
        var removed: Pair<SourceId, String>? = null
        val actions = NotesActions(
            onCreateCategory = { source, name -> created = source to name },
            onRenameCategory = { source, old, new -> renamed = Triple(source, old, new) },
            onDeleteCategory = { source, path -> removed = source to path },
        )
        compose.setContent { KgsNotesTheme { KgsNotesScreen(state.value, actions) } }
        compose.onNodeWithTag("settings-open-CATEGORIES").performClick()
        compose.onNodeWithTag("settings-new-category").performClick()
        compose.onNodeWithTag("new-category-field").performTextInput("Ideas")
        compose.onNodeWithTag("new-category-confirm").performClick()
        compose.runOnIdle { assertEquals(LocalSourceId to "Ideas", created) }

        compose.onNodeWithTag("manage-category-${LocalSourceId.value}-Work").performClick()
        compose.onNodeWithTag("rename-category-field").performTextReplacement("Office")
        compose.onNodeWithTag("category-rename-confirm").performClick()
        compose.runOnIdle { assertEquals(Triple(LocalSourceId, "Work", "Office"), renamed) }

        compose.onNodeWithTag("category-remove-${LocalSourceId.value}-Work").performClick()
        compose.onNodeWithTag("category-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(LocalSourceId to "Work", removed) }
    }

    @Test fun germanChromeLeavesUserTitlesAndCategoriesUntouched() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("de"))) {
                KgsNotesTheme { KgsNotesScreen(NotesUiState(
                    notes = listOf(summary("My English title").copy(category = "My Category")),
                    categories = listOf("My Category"),
                ), NotesActions()) }
            }
        }
        compose.onNodeWithText("My English title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menü öffnen").performClick()
        compose.onNodeWithText("Alle Notizen").assertIsDisplayed()
        compose.onNodeWithText("Papierkorb").assertIsDisplayed()
        compose.onNodeWithText("My Category").assertIsDisplayed()
    }

    @Test fun tabletBookAndTabletopLayoutsKeepPanesApartAndAllowFullScreen() {
        val layout = mutableStateOf(EditorLayoutPreference.AUTOMATIC)
        val posture = mutableStateOf(0)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 850.dp))) {
                val density = LocalDensity.current
                val fold = with(density) { when (posture.value) {
                    1 -> NotesFold(Rect(490.dp.roundToPx(), 0, 510.dp.roundToPx(), 850.dp.roundToPx()), false)
                    2 -> NotesFold(Rect(0, 415.dp.roundToPx(), 1000.dp.roundToPx(), 435.dp.roundToPx()), true)
                    else -> null
                } }
                KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(summary("Library fixture")),
                    selectedNote = note(), editorMode = EditorMode.SOURCE, editorLayout = layout.value, fold = fold,
                ), NotesActions(onEditorLayoutChange = { layout.value = it })) }
            }
        }
        compose.onNodeWithTag("notes-split-layout").assertExists()
        fun horizontalPanes() {
            val library = compose.onNodeWithTag("notes-search").fetchSemanticsNode().boundsInRoot
            val editor = compose.onNodeWithTag("source-editor").fetchSemanticsNode().boundsInRoot
            assertTrue(library.right < editor.left)
        }
        horizontalPanes()
        compose.onNodeWithContentDescription("Full-screen Note").performClick()
        compose.onNodeWithTag("notes-full-screen-layout").assertExists()
        compose.onNodeWithTag("notes-search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show library").performClick()
        compose.runOnIdle { posture.value = 1 }
        compose.onNodeWithTag("notes-book-layout").assertExists()
        horizontalPanes()
        compose.runOnIdle { posture.value = 2 }
        compose.onNodeWithTag("notes-tabletop-layout").assertExists()
        val library = compose.onNodeWithTag("notes-search").fetchSemanticsNode().boundsInRoot
        val editor = compose.onNodeWithTag("source-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(editor.bottom < library.top)
    }

    @Test fun longPressingANoteBesideAnOpenNoteOffersItsActions() {
        val fixture = summary("Library fixture")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 850.dp))) {
                KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(fixture), selectedNote = note(),
                    editorMode = EditorMode.SOURCE), NotesActions()) }
            }
        }
        compose.onNodeWithTag("notes-split-layout").assertExists()
        compose.onNodeWithTag("note-card-${fixture.id.value}").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun bookPostureShowsTwoPagesThatOpenNotesIndependently() {
        val left = summary("Left page")
        val opened = mutableListOf<Pair<NotePane, NoteId>>()
        var activePane = NotePane.PRIMARY
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 850.dp))) {
                val fold = with(LocalDensity.current) {
                    NotesFold(Rect(490.dp.roundToPx(), 0, 510.dp.roundToPx(), 850.dp.roundToPx()), false)
                }
                KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(left), selectedNote = note(),
                    editorMode = EditorMode.SOURCE, fold = fold),
                    NotesActions(onActivatePane = { activePane = it }, onSelect = { opened += activePane to it })) }
            }
        }
        compose.onNodeWithTag("notes-book-layout").assertExists()
        // Only the left page carries the menu; the right page keeps its open Note.
        compose.onAllNodesWithContentDescription("Open menu").assertCountEquals(1)
        compose.onNodeWithTag("source-editor").assertIsDisplayed()
        compose.onNodeWithTag("note-card-${left.id.value}").performClick()
        compose.runOnIdle { assertEquals(listOf(NotePane.SECONDARY to left.id), opened) }
    }

    @Test fun sheetsOpenOnTheSideOfTheSplitScreenTheyComeFrom() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 850.dp))) {
                KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(summary("Library fixture")), selectedNote = note(),
                    editorMode = EditorMode.SOURCE), NotesActions()) }
            }
        }
        compose.onNodeWithTag("library-view-options").performClick()
        compose.waitForIdle()
        val library = compose.onNodeWithTag("library-pane").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("view-options-menu").fetchSemanticsNode().boundsInRoot
        assertTrue("The sheet must stay within the library pane: $sheet in $library",
            sheet.left >= library.left - 1f && sheet.right <= library.right + 1f && sheet.bottom <= library.bottom + 1f)
    }

    @Test fun openingASheetOnOneSideClosesTheSheetOpenOnTheOther() {
        val fixture = summary("Library fixture")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 850.dp))) {
                KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(fixture), selectedNote = note(),
                    editorMode = EditorMode.SOURCE), NotesActions()) }
            }
        }
        compose.onNodeWithTag("note-card-${fixture.id.value}").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose Category").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test fun sheetsStayContentSizedAndConstrainedOnLargeScreens() {
        // Modal sheets render in their own window, which ForcedSize cannot resize and which
        // provides its own density. Measure in that window's density: on the tablet runner
        // the window is genuinely wide, and on phones the cap must still hold.
        var pixelsPerDp = 0f
        var windowWidth = 0f
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(1200.dp, 850.dp))) {
                KgsNotesTheme {
                    com.kgs.notes.design.KgsBottomSheet(true, {}, modifier = androidx.compose.ui.Modifier.testTag("wide-sheet")) {
                        pixelsPerDp = LocalDensity.current.density
                        windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()
                        androidx.compose.material3.Text("Sheet content")
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("wide-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(pixelsPerDp > 0f)
        assertTrue("Sheets must be capped at 640 dp: ${sheet.width}px at $pixelsPerDp", sheet.width <= 640f * pixelsPerDp + 1f)
        assertTrue("Sheets fill narrower windows", sheet.width >= minOf(windowWidth, 640f * pixelsPerDp) - 1f)
        assertTrue(sheet.height < 400f * pixelsPerDp)
    }

    private fun note() = Note(id = NoteId("fixture"), sourceId = LocalSourceId, syncState = NoteSyncState.LOCAL_SOURCE,
        title = "Fixture title", markdown = "Body", category = "", favorite = false,
        state = NoteState.ACTIVE, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
    private fun summary(title: String) = NoteSummary(id = NoteId(title), sourceId = LocalSourceId, syncState = NoteSyncState.LOCAL_SOURCE,
        title = title, snippet = "Body", category = "", favorite = false,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
}
