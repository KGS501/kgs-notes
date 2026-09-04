package com.kgs.notes

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.engine.LocalNotesEngine
import com.kgs.notes.engine.AttachmentId
import com.kgs.notes.engine.AttachmentImport
import com.kgs.notes.engine.LibrarySnapshot
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.NotesEngine
import com.kgs.notes.engine.ManagedAttachment
import com.kgs.notes.engine.SourceId
import com.kgs.notes.engine.Source
import com.kgs.notes.engine.SourceCapabilities
import com.kgs.notes.engine.SourceDescriptor
import java.io.InputStream
import java.util.Date
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun settingsOpensAStockNextcloudConnectionFlow() {
        val connectionVisible = mutableStateOf(false)
        var submittedServer = ""
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        destination = LibraryDestination.SETTINGS,
                        sourceConnection = SourceConnectionUiState(visible = connectionVisible.value),
                    ),
                    actions = NotesActions(
                        onAddSource = { connectionVisible.value = true },
                        onBrowserSourceConnect = { _, server -> submittedServer = server },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Add Nextcloud Source").performClick()
        compose.onNodeWithTag("nextcloud-connection-dialog").assertIsDisplayed()
        compose.onNodeWithTag("nextcloud-address").performTextInput("cloud.example.com")
        compose.onNodeWithText("Continue in browser").performClick()

        assertEquals("cloud.example.com", submittedServer)
    }

    @Test
    fun newNoteSquircleExpandsTowardTheFullEditorBounds() {
        val selected = mutableStateOf<Note?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        selectedNote = selected.value,
                        editorMode = com.kgs.notes.editor.EditorMode.SOURCE,
                    ),
                    actions = NotesActions(onCreate = { selected.value = sampleNote() }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val startBounds = compose.onNodeWithContentDescription("New note")
            .fetchSemanticsNode().boundsInRoot
        val screenWidth = compose.onNodeWithTag("library-pane")
            .fetchSemanticsNode().boundsInRoot.width

        compose.onNodeWithContentDescription("New note").performClick()
        compose.mainClock.advanceTimeBy(90)
        val expandingBounds = compose.onNodeWithTag("note-editor")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "The editor container should already be growing out of the New Note squircle",
            expandingBounds.width > startBounds.width * 1.25f,
        )
        repeat(36) {
            compose.mainClock.advanceTimeBy(16)
            // Force every overshooting spring frame through the real draw path.
            compose.onRoot().captureToImage()
        }
        compose.mainClock.advanceTimeBy(200)
        val finalBounds = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot
        assertEquals("The morph must resolve to the full-screen editor", screenWidth, finalBounds.width, 1f)
    }

    @Test
    fun appButtonsGiveOneShortHapticPulsePerTap() {
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme {
                    KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
                }
            }
        }

        compose.onNodeWithContentDescription("Favorite notes").performClick()

        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.VirtualKey), feedback)
        }
    }

    @Test
    fun libraryUsesTheSameBlueCanvasAsKgsCalendar() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
            }
        }

        val image = compose.onNodeWithTag("library-pane").captureToImage().toPixelMap()

        assertEquals(Color(0xFFEDF6FF), image[2, image.height / 2])
    }

    @Test
    fun modalPopupsAreWhiteWithAppBlueSearchFields() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(),
                )
            }
        }
        compose.onNodeWithContentDescription("Choose Category").performClick()

        val popup = compose.onNodeWithTag("category-dialog").captureToImage().toPixelMap()
        val search = compose.onNodeWithTag("category-search").captureToImage().toPixelMap()

        assertEquals(Color.White, popup[2, popup.height / 2])
        assertEquals(Color(0xFFEDF6FF), search[search.width - 20, search.height / 2])
    }

    @Test
    fun searchFieldsGiveOneShortHapticPulseWhenTapped() {
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme {
                    KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
                }
            }
        }

        compose.onNodeWithTag("notes-search").performClick()

        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.VirtualKey), feedback)
        }
    }

    @Test
    fun aQuickFilterTapKeepsTheExpressiveGroupMotionVisible() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val favorite = compose.onNodeWithContentDescription("Favorite notes")
        val restingWidth = favorite.fetchSemanticsNode().boundsInRoot.width

        favorite.performTouchInput {
            down(center)
            advanceEventTime(16)
            up()
        }
        compose.mainClock.advanceTimeBy(72)

        val quickTapWidth = favorite.fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "A quick tap should still reveal most of the connected-button growth",
            quickTapWidth >= restingWidth + 3f,
        )
    }

    @Test
    fun selectedFilterMorphsToItsActiveIcon() {
        val filter = mutableStateOf(LibraryFilter.ALL)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(filter = filter.value),
                    actions = NotesActions(onFilterChange = { filter.value = it }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val favorite = compose.onNodeWithContentDescription("Favorite notes")
        val beforeMap = favorite.captureToImage().toPixelMap()
        val center = beforeMap.width / 2 to beforeMap.height / 2
        val before = beforeMap[center.first, center.second]

        favorite.performClick()
        compose.mainClock.advanceTimeBy(45)
        val changing = favorite.captureToImage().toPixelMap()[center.first, center.second]
        compose.mainClock.advanceTimeBy(500)
        val active = favorite.captureToImage().toPixelMap()[center.first, center.second]

        assertNotEquals("The active filter icon should visibly transition", before, changing)
        assertNotEquals("The transition should not jump straight to its final icon", changing, active)
        assertEquals(Color(0xFFD13B4B), active)
    }

    @Test
    fun homeControlsUseShapeMotionWithoutAStockGreyPressOverlay() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val favorite = compose.onNodeWithContentDescription("Favorite notes")
        val beforeMap = favorite.captureToImage().toPixelMap()
        val sample = Offset(10f, beforeMap.height / 2f)
        val before = beforeMap[sample.x.toInt(), sample.y.toInt()]

        favorite.performTouchInput { down(sample) }
        compose.mainClock.advanceTimeBy(120)
        val pressed = favorite.captureToImage().toPixelMap()[sample.x.toInt(), sample.y.toInt()]
        favorite.performTouchInput { up() }

        assertEquals("Expressive shape motion must not be covered by a stock grey indication", before, pressed)
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
        compose.onNodeWithTag("dropdown-item-Favourite").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-item-Delete").assertIsDisplayed()
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
        compose.onNode(hasText("Local Source") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun sourceFallbackDeletesAMultiLineSelectionAsOneEdit() {
        val original = "Before\n<table>\nAfter"
        var changed = original
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        selectedNote = sampleNote().copy(markdown = original),
                        editorMode = com.kgs.notes.editor.EditorMode.RICH,
                    ),
                    actions = NotesActions(onContentChange = { changed = it }),
                )
            }
        }

        compose.onNodeWithText("Source Mode").assertIsDisplayed()
        val sourceEditor = compose.onNodeWithTag("source-editor")
        sourceEditor.performTextInputSelection(TextRange(6, 14))
        sourceEditor.performKeyInput { pressKey(Key.Backspace) }

        compose.runOnIdle {
            assertEquals("Before\nAfter", changed)
        }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil(5_000) { changed == original }
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.waitUntil(5_000) { changed == "Before\nAfter" }
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
        compose.onNodeWithTag("dropdown-item-Bulleted list").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-item-Checklist").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-item-Numbered list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Redo").assertIsDisplayed()
    }

    @Test
    fun editorToolbarOffersAReachableInlineImageAction() {
        var requested = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(onAddImage = { requested = true }),
                )
            }
        }

        compose.onNodeWithContentDescription("Add image").performClick()

        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun imageImportMakesThePrivateMetadataChoiceExplicitBeforePicking() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val root = application.cacheDir.resolve("image-import-${System.nanoTime()}")
            val engine = LocalNotesEngine.open(root.toPath())
            val noteId = engine.createDraft()
            engine.updateContent(noteId, "# Field notes")
            val viewModel = NotesViewModel(application, engine)
            viewModel.selectNote(noteId)
            compose.setContent {
                KgsNotesTheme { KgsNotesApp(viewModel) }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("note-editor").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithContentDescription("Add image").performClick()

            compose.onNodeWithText("Add an image").assertIsDisplayed()
            compose.onNode(hasText("Remove private metadata") and hasClickAction()).assertIsDisplayed()
            compose.onNode(hasText("Keep original") and hasClickAction()).assertIsDisplayed()
            root.deleteRecursively()
        }
    }

    @Test
    fun editorToolbarReflectsActiveRichFormatting() {
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(),
                )
            }
        }

        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Bold").performClick()
        compose.waitUntil(8_000) {
            runCatching {
                compose.onNodeWithContentDescription("Bold").assertIsSelected()
            }.isSuccess
        }
        compose.onNodeWithContentDescription("Bold").assertIsSelected()
    }

    @Test
    fun longPressShowsAButtonNameWithoutActivatingIt() {
        var activations = 0
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(),
                    actions = NotesActions(onFilterChange = { activations += 1 }),
                )
            }
        }

        compose.onNodeWithContentDescription("Favorite notes").performTouchInput { longClick() }

        assertEquals(0, activations)
        compose.onNodeWithText("Favorite notes").assertIsDisplayed()
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
        compose.onNodeWithTag("dropdown-item-Last edited").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-item-Last created").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-item-Alphabetical").assertIsDisplayed()
        compose.onNodeWithTag("dropdown-leading-Last edited", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dropdown-leading-Last created", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dropdown-leading-Alphabetical", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Selected: Last edited").assertIsDisplayed()

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
    fun noteCardsPlaceFavoriteAndLocalizedLastEditedDetailsInTheFooter() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val today = Instant.now()
        val old = Instant.parse("2001-01-01T12:00:00Z")
        val todaySummary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Today note")
            .copy(favorite = true, updatedAt = today)
        val oldSummary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Old note")
            .copy(updatedAt = old)
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(todaySummary, oldSummary)),
                    actions = NotesActions(),
                )
            }
        }

        val todayText = DateFormat.getTimeFormat(context).format(Date.from(today))
        val oldText = DateFormat.getDateFormat(context).format(Date.from(old))
        compose.onNodeWithText(todayText).assertIsDisplayed()
        compose.onNodeWithText(oldText).assertIsDisplayed()

        val titleBounds = compose.onNodeWithText("Today note", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val favoriteBounds = compose.onNodeWithContentDescription("Favorite", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dateBounds = compose.onNodeWithText(todayText, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("note-card-today-note").fetchSemanticsNode().boundsInRoot
        assertTrue("Favorite belongs in the card footer", favoriteBounds.top > titleBounds.bottom)
        assertTrue("Favorite belongs at the footer's left edge", favoriteBounds.left < dateBounds.left)
        assertTrue(
            "Last edited belongs at the card's bottom right: date=${dateBounds.right}, card=${cardBounds.right}",
            dateBounds.right > cardBounds.right - 20f * context.resources.displayMetrics.density,
        )
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
    fun settingsOffersAnAttachmentMetadataDefault() {
        val preference = mutableStateOf(AttachmentMetadataPreference.ASK_EVERY_TIME)
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(
                        destination = LibraryDestination.SETTINGS,
                        attachmentMetadataPreference = preference.value,
                    ),
                    actions = NotesActions(
                        onAttachmentMetadataPreferenceChange = { preference.value = it },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Attachment metadata: Ask every time").performClick()
        compose.onAllNodesWithText("Ask every time").assertCountEquals(2)
        compose.onNodeWithText("Remove private metadata").performClick()

        assertEquals(AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA, preference.value)
        compose.onNodeWithText("Remove private metadata").assertIsDisplayed()
    }

    @Test
    fun attachmentMetadataDefaultSurvivesViewModelRecreation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = application.getSharedPreferences(
            "attachment-metadata-${System.nanoTime()}",
            Application.MODE_PRIVATE,
        )
        val engine = ControlledNotesEngine(LibrarySnapshot())
        val first = NotesViewModel(application, engine, preferences)
        first.setAttachmentMetadataPreference(AttachmentMetadataPreference.KEEP_ORIGINAL)
        val recreated = NotesViewModel(application, engine, preferences)
        recreated.openDestination(LibraryDestination.SETTINGS)
        compose.setContent {
            KgsNotesTheme { KgsNotesApp(recreated) }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Keep original").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Keep original").assertIsDisplayed()
        preferences.edit().clear().commit()
    }

    @Test
    fun settingsExitKeepsBothDestinationsComposedDuringItsTransition() {
        val destination = mutableStateOf(LibraryDestination.SETTINGS)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    state = NotesUiState(destination = destination.value),
                    actions = NotesActions(onDestinationChange = { destination.value = it }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithContentDescription("Back to Notes").performClick()
        compose.mainClock.advanceTimeBy(16)

        compose.onNodeWithText("Default Source").assertIsDisplayed()
        compose.onNodeWithText("Search notes").assertIsDisplayed()
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
        override val sources = MutableStateFlow(
            listOf(
                SourceDescriptor(
                    id = LocalSourceId,
                    name = "Local Source",
                    capabilities = SourceCapabilities(true, true, true, true, true),
                ),
            ),
        )

        override suspend fun createDraft(category: String, sourceId: SourceId) = error("Not used")
        override suspend fun attachSource(source: Source) = Unit
        override suspend fun refreshSources() = Unit
        override suspend fun synchronize(id: NoteId) = Unit
        override suspend fun moveToSource(id: NoteId, sourceId: SourceId) = id
        override suspend fun note(id: NoteId): Note? = null
        override suspend fun updateContent(id: NoteId, markdown: String) = Unit
        override suspend fun rename(id: NoteId, title: String) = Unit
        override suspend fun setCategory(id: NoteId, category: String) = Unit
        override suspend fun importManagedAttachment(
            noteId: NoteId,
            attachment: AttachmentImport,
        ): ManagedAttachment = error("Not used")
        override suspend fun managedAttachments(noteId: NoteId): List<ManagedAttachment> = emptyList()
        override fun openManagedAttachment(id: AttachmentId): InputStream? = null
        override suspend fun toggleFavorite(id: NoteId) = Unit
        override suspend fun moveToTrash(id: NoteId) = Unit
        override suspend fun restore(id: NoteId) = Unit
        override suspend fun deletePermanently(id: NoteId) = Unit
        override suspend fun close(id: NoteId) = Unit
    }
}
