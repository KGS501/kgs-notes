package com.kgs.notes

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.kgs.notes.design.NotesTheme
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
    fun searchAndMenuShareTheFirstRowAndDrawerShowsTheLogo() {
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(), NotesActions()) } }
        compose.onAllNodesWithText("K").assertCountEquals(0)
        compose.onAllNodesWithText("Notes").assertCountEquals(0)
        val menu = compose.onNodeWithContentDescription("Open menu").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("notes-search").fetchSemanticsNode().boundsInRoot
        assertEquals(menu.center.y, search.center.y, 1f)
        assertTrue(search.left > menu.right)
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithContentDescription("KGS Notes logo").assertIsDisplayed()
        compose.onAllNodesWithText("KGS Notes").assertCountEquals(0)
        val logo = compose.onNodeWithContentDescription("KGS Notes logo").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("sidebar-branding").fetchSemanticsNode().boundsInRoot
        assertEquals(header.center.x, logo.center.x, 1f)
        assertTrue("Sidebar wordmark should stay modest", logo.width < header.width * .4f)
        compose.onAllNodesWithText("Portable by design").assertCountEquals(0)
        compose.onAllNodesWithText("K").assertCountEquals(0)
    }

    @Test
    fun darkThemeUsesDarkButtonMenuSelectionAndToolbarSurfaces() {
        val summary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        val state = mutableStateOf(NotesUiState(notes = listOf(summary)))
        compose.setContent {
            KgsNotesTheme(darkTheme = true) { KgsNotesScreen(state.value, NotesActions()) }
        }
        fun assertDarkSurface(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
            val pixels = node.captureToImage().toPixelMap()
            var bright = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].luminance() > .75f) bright++
            }
            assertTrue("Surface should adapt to dark mode", bright.toFloat() / (pixels.width * pixels.height) < .25f)
        }
        assertDarkSurface(compose.onNodeWithContentDescription("Favorite notes"))
        assertDarkSurface(compose.onNodeWithContentDescription("Card view"))
        compose.onNodeWithContentDescription("Sort notes").performClick()
        assertDarkSurface(compose.onNodeWithTag("sort-options-menu"))
        compose.onNodeWithText("Last edited").performClick()
        compose.onNodeWithTag("note-card-${summary.id.value}").performTouchInput { longClick() }
        assertDarkSurface(compose.onNodeWithTag("note-selection-bar"))
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(selectedNote = sampleNote(), editorMode = com.kgs.notes.editor.EditorMode.SOURCE)
        }
        assertDarkSurface(compose.onNodeWithTag("editor-toolbar"))
    }

    @Test
    fun closingMorphDoesNotStretchASingleLineIntoTheEditorsHeight() {
        val note = sampleNote().copy(markdown = "MMMMMMMM")
        val summary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
            .copy(id = note.id, previewMarkdown = note.markdown, snippet = note.markdown)
        val selected = mutableStateOf<Note?>(null)
        compose.setContent {
            KgsNotesTheme(darkTheme = false) {
                androidx.compose.material3.MaterialTheme(
                    colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(onSurfaceVariant = Color.Red),
                ) {
                KgsNotesScreen(NotesUiState(notes = listOf(summary), selectedNote = selected.value,
                    editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    NotesActions(onSelect = { selected.value = note }, onCloseEditor = { selected.value = null }))
                }
            }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back to Notes").performClick()
        val maxInkRows = 42 * ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
        for (frame in 1..4) {
            compose.mainClock.advanceTimeBy(64)
            val pixels = compose.onNodeWithTag("note-preview-${note.id.value}").captureToImage().toPixelMap()
            val inkRows = (0 until pixels.height).count { y ->
                (0 until pixels.width / 2).any { x ->
                    val pixel = pixels[x, y]
                    pixel.red > .7f && pixel.green < .45f && pixel.blue < .45f
                }
            }
            assertTrue("One line must not stretch to the editor height on frame $frame", inkRows < maxInkRows)
        }
    }

    @Test
    fun selectionSheetKeepsOtherNotesSelectableAndEmptySpaceDismissesIt() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First selection fixture")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second selection fixture")
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(first, second)), NotesActions()) } }
        compose.onNodeWithTag("note-card-${first.id.value}").performTouchInput { longClick() }
        compose.onNodeWithTag("note-card-${second.id.value}").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("library-pane").performTouchInput {
            click(Offset(width * .5f, height * .55f))
        }
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun renameUsesTheSameBrightFocusOutlineAsSearch() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        compose.setContent { KgsNotesTheme(darkTheme = false) { KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions()) } }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Rename").performClick()
        val field = compose.onNodeWithTag("rename-note-field").performClick()
        val pixels = field.captureToImage().toPixelMap()
        assertEquals(Color(0xFFFFC217), pixels[pixels.width / 2, 1])
    }

    @Test
    fun trashBodyAndRestoreLabelAreReadableInDarkMode() {
        val note = sampleNote().copy(state = NoteState.TRASHED, markdown = "Deleted fixture")
        compose.setContent {
            KgsNotesTheme(darkTheme = true) { KgsNotesScreen(NotesUiState(selectedNote = note), NotesActions()) }
        }
        for (text in listOf("Deleted fixture", "Restore")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$text should use a light foreground", layouts.single().layoutInput.style.color.luminance() > .5f)
        }
    }

    @Test
    fun plusIsWhiteInLightModeAndDarkInDarkModeForEitherTheme() {
        val dark = mutableStateOf(false)
        val theme = mutableStateOf(NotesTheme.KGS_NOTES)
        compose.setContent { KgsNotesTheme(darkTheme = dark.value, theme = theme.value) { KgsNotesScreen(NotesUiState(), NotesActions()) } }
        for (choice in NotesTheme.entries) {
            for (darkMode in listOf(false, true)) {
                compose.runOnIdle { theme.value = choice; dark.value = darkMode }
                compose.mainClock.advanceTimeBy(1_000)
                val icon = compose.onNodeWithContentDescription("New note", useUnmergedTree = true).captureToImage().toPixelMap()
                val luminance = icon[icon.width / 2, icon.height / 2].luminance()
                assertTrue("Plus contrast for $choice, dark=$darkMode", if (darkMode) luminance < .2f else luminance > .9f)
            }
        }
    }

    @Test
    fun yellowThemeIsDefaultAndBlueChoiceSurvivesReopeningTheApp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "theme-choice-")
        val preferences = application.getSharedPreferences("theme-${root.fileName}", Application.MODE_PRIVATE)
        val engine = ControlledNotesEngine(LibrarySnapshot())
        fun fresh() = NotesViewModel(application, engine, preferences,
            accountStore = NextcloudAccountStore(root.resolve("accounts"))).also { it.openDestination(LibraryDestination.SETTINGS) }
        val model = mutableStateOf(fresh())
        compose.setContent { KgsNotesApp(model.value) }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("theme-choice-KGS_NOTES").assertIsSelected()
        compose.onNodeWithTag("theme-choice-BLUE").performClick()
        compose.onNodeWithTag("theme-choice-BLUE").assertIsSelected()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.runOnIdle { model.value = fresh() }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("theme-choice-BLUE").assertIsSelected()
        compose.onNodeWithTag("theme-choice-KGS_NOTES").performClick()
        compose.onNodeWithTag("theme-choice-KGS_NOTES").assertIsSelected()
    }

    @Test
    fun dateSectionsCanBeTurnedOffWithoutAddingAFavoriteSectionInEitherLayout() {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        fun dated(title: String, days: Long) = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, title)
            .copy(updatedAt = today.minusDays(days).atTime(12, 0).atZone(zone).toInstant())
        val notes = listOf(dated("Favorite example", 40).copy(favorite = true), dated("Recent example", 0),
            dated("Yesterday example", 1), dated("Week example", 4), dated("Month example", 20))
        val grouped = mutableStateOf(true)
        val view = mutableStateOf(LibraryView.LIST)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(notes = notes, groupByDate = grouped.value, libraryView = view.value), NotesActions()) } }
        compose.onNodeWithTag("notes-section-Pinned").assertDoesNotExist()
        compose.onNodeWithTag("notes-section-Today").assertIsDisplayed()
        compose.onNodeWithTag("notes-list").performScrollToIndex(2)
        compose.onNodeWithTag("notes-section-Yesterday").assertExists()
        compose.onNodeWithTag("notes-list").performScrollToIndex(3)
        compose.onNodeWithTag("notes-section-Last 7 Days").assertExists()
        compose.onNodeWithTag("notes-list").performScrollToIndex(4)
        compose.onNodeWithTag("notes-section-Last 30 Days").assertExists()
        compose.runOnIdle { view.value = LibraryView.CARDS }
        compose.onNodeWithTag("notes-cards").performScrollToIndex(1)
        compose.onNodeWithTag("notes-section-Today").assertExists()
        compose.runOnIdle { grouped.value = false }
        compose.onNodeWithTag("notes-cards").performScrollToIndex(0)
        compose.onAllNodesWithTag("notes-section-Today").assertCountEquals(0)
        compose.onNodeWithTag("notes-section-Pinned").assertDoesNotExist()
        compose.onNodeWithTag("notes-section-Notes").assertDoesNotExist()
    }

    @Test
    fun dateSectionsUseCalendarBoundariesAndCreationSortUsesCreationDates() {
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        fun dated(title: String, days: Long) = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, title).copy(
            updatedAt = today.minusDays(days).atTime(12, 0).atZone(zone).toInstant(),
            createdAt = today.atTime(1, 0).atZone(zone).toInstant())
        val notes = listOf(dated("Six days", 6), dated("Seven days", 7), dated("Twenty nine days", 29), dated("Thirty days", 30))
        val sort = mutableStateOf(LibrarySort.LAST_EDITED)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = notes, sort = sort.value, groupByDate = true), NotesActions()) } }
        compose.onNodeWithTag("notes-section-Last 7 Days").assertIsDisplayed()
        compose.onNodeWithTag("notes-section-Last 30 Days").assertExists()
        val week = compose.onNodeWithTag("note-card-${notes[0].id.value}").fetchSemanticsNode().boundsInRoot
        val month = compose.onNodeWithTag("notes-section-Last 30 Days").fetchSemanticsNode().boundsInRoot
        assertTrue(week.bottom <= month.top)
        compose.onNodeWithTag("notes-list").performScrollToIndex(3)
        val older = today.minusDays(30).withDayOfMonth(1)
        val label = older.format(java.time.format.DateTimeFormatter.ofPattern(
            if (older.year == today.year) "MMMM" else "MMMM yyyy", java.util.Locale.getDefault()))
        compose.onNodeWithTag("notes-section-$label").assertExists()
        compose.runOnIdle { sort.value = LibrarySort.LAST_CREATED }
        compose.onNodeWithTag("notes-list").performScrollToIndex(0)
        compose.onNodeWithTag("notes-section-Today").assertExists()
        compose.onAllNodesWithTag("notes-section-Last 7 Days").assertCountEquals(0)
        compose.onAllNodesWithTag("notes-section-Last 30 Days").assertCountEquals(0)
    }

    @Test
    fun dateGroupingIsOffByDefaultAndExplicitChoiceSurvivesReopening() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "date-grouping-")
        val preferences = application.getSharedPreferences("grouping-${root.fileName}", Application.MODE_PRIVATE)
        val engine = ControlledNotesEngine(LibrarySnapshot())
        fun fresh() = NotesViewModel(application, engine, preferences,
            accountStore = NextcloudAccountStore(root.resolve("accounts"))).also { it.openDestination(LibraryDestination.SETTINGS) }
        val model = mutableStateOf(fresh())
        compose.setContent { KgsNotesApp(model.value) }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("settings-page-APPEARANCE").performScrollToNode(hasTestTag("group-by-date"))
        compose.onNodeWithTag("group-by-date").assertIsOff()
        compose.onNodeWithTag("group-by-date").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.runOnIdle { model.value = fresh() }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("settings-page-APPEARANCE").performScrollToNode(hasTestTag("group-by-date"))
        compose.onNodeWithTag("group-by-date").assertIsOn()
    }

    @Test
    fun settingsHubGroupsSourcesAndSubpageBackReturnsToTheHub() {
        var closed = false
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(destination = LibraryDestination.SETTINGS),
            NotesActions(onDestinationChange = { closed = it == LibraryDestination.NOTES })) } }
        compose.onNodeWithTag("settings-page-HOME").assertIsDisplayed()
        compose.onAllNodesWithTag("source-settings-${LocalSourceId.value}").assertCountEquals(0)
        compose.onNodeWithTag("settings-open-SOURCES").performClick()
        compose.onNodeWithTag("source-settings-${LocalSourceId.value}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.onNodeWithTag("settings-page-HOME").assertIsDisplayed()
        assertTrue(!closed)
        compose.onNodeWithContentDescription("Back to Notes").performClick()
        assertTrue(closed)
    }

    @Test
    fun settingsProjectButtonsOpenSponsorsAndNotesIssues() {
        val opened = mutableListOf<String>()
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(destination = LibraryDestination.SETTINGS),
            NotesActions(onOpenProjectLink = { opened += it })) } }
        compose.onNodeWithTag("settings-page-HOME").performScrollToNode(hasTestTag("settings-sponsor"))
        compose.onNodeWithTag("settings-sponsor").performClick()
        compose.onNodeWithTag("settings-page-HOME").performScrollToNode(hasTestTag("settings-report-bug"))
        compose.onNodeWithTag("settings-report-bug").performClick()
        assertEquals(listOf("https://github.com/sponsors/KGS501", "https://github.com/KGS501/kgs-notes/issues"), opened)
    }

    @Test
    fun darkModeChoiceOverridesTheSystemAndSurvivesReopeningTheApp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "dark-mode-")
        val preferences = application.getSharedPreferences("dark-mode-${root.fileName}", Application.MODE_PRIVATE)
        val engine = ControlledNotesEngine(LibrarySnapshot())
        fun fresh() = NotesViewModel(application, engine, preferences,
            accountStore = NextcloudAccountStore(root.resolve("accounts"))).also { it.openDestination(LibraryDestination.SETTINGS) }
        fun brightness(): Float {
            val pixels = compose.onNodeWithTag("settings-page-APPEARANCE").captureToImage().toPixelMap()
            var bright = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].luminance() > .75f) bright++
            return bright.toFloat() / (pixels.width * pixels.height)
        }
        val model = mutableStateOf(fresh())
        compose.setContent { KgsNotesApp(model.value) }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("dark-mode-SYSTEM").assertIsSelected()
        compose.onNodeWithTag("dark-mode-DARK").performClick().assertIsSelected()
        compose.waitForIdle()
        assertTrue("Dark mode should apply regardless of the system setting", brightness() < .25f)
        compose.onNodeWithTag("dark-mode-LIGHT").performClick().assertIsSelected()
        compose.waitForIdle()
        assertTrue("Light mode should apply regardless of the system setting", brightness() > .5f)
        compose.onNodeWithTag("dark-mode-DARK").performClick()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.runOnIdle { model.value = fresh() }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("dark-mode-DARK").assertIsSelected()
        preferences.edit().clear().commit()
    }

    @Test
    fun defaultEditorModeAppliesToNotesWithoutTheirOwnModeAndSurvivesReopening() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = application.cacheDir.resolve("default-editor-mode-${System.nanoTime()}")
        val preferences = application.getSharedPreferences("default-editor-mode-${System.nanoTime()}", Application.MODE_PRIVATE)
        val engine = LocalNotesEngine.open(root.toPath())
        val id = engine.createDraft()
        engine.updateContent(id, "# Field notes\n**bold**")
        engine.close(id)
        val first = NotesViewModel(application, engine, preferences).also { it.openDestination(LibraryDestination.SETTINGS) }
        val model = mutableStateOf(first)
        compose.setContent { KgsNotesApp(model.value) }
        compose.onNodeWithTag("settings-open-NOTES").performClick()
        compose.onNodeWithTag("editor-mode-RICH").assertIsSelected()
        compose.onNodeWithTag("editor-mode-SOURCE").performClick().assertIsSelected()
        first.openDestination(LibraryDestination.NOTES)
        compose.waitUntil(5_000) { first.state.value.previewModes[id] == com.kgs.notes.editor.EditorMode.SOURCE }
        compose.onNodeWithText("**bold**").assertIsDisplayed()
        first.selectNote(id)
        compose.waitUntil(5_000) { first.state.value.selectedNote?.id == id }
        assertEquals(com.kgs.notes.editor.EditorMode.SOURCE, first.state.value.editorMode)

        val reopened = NotesViewModel(application, engine, preferences)
        compose.runOnIdle { model.value = reopened }
        reopened.createNote()
        compose.waitUntil(5_000) { reopened.state.value.selectedNote != null }
        assertEquals(com.kgs.notes.editor.EditorMode.SOURCE, reopened.state.value.editorMode)
        preferences.edit().clear().commit()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun aboutShowsTheVersionLicenseAndOpenSourceNotices() {
        val opened = mutableListOf<String>()
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(destination = LibraryDestination.SETTINGS),
            NotesActions(onOpenProjectLink = { opened += it })) } }
        compose.onNodeWithTag("settings-page-HOME").performScrollToNode(hasTestTag("settings-open-ABOUT"))
        compose.onNodeWithTag("settings-open-ABOUT").performClick()
        compose.onNodeWithTag("about-version").assertIsDisplayed()
        compose.onNodeWithText("GPL-3.0-or-later", substring = true).assertIsDisplayed()
        compose.onNodeWithText("© 2026 KGS501", substring = true).assertExists()
        compose.onNodeWithTag("about-source-code").performClick()
        compose.onNodeWithTag("about-privacy-policy").performClick()
        assertEquals(listOf("https://github.com/KGS501/kgs-notes", "https://github.com/KGS501/kgs-notes/blob/main/index.html"), opened)

        // License texts ship with the app, so they open without a connection.
        compose.onNodeWithTag("about-license").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back to About").performClick()
        compose.onNodeWithTag("about-open-source-licenses").performClick()
        compose.onNodeWithTag("library-Tiptap").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tiptap GmbH", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back to Open-source licenses").performClick()
        compose.onNodeWithContentDescription("Back to About").performClick()
        compose.onNodeWithTag("about-version").assertIsDisplayed()
        assertEquals(2, opened.size)
    }

    @Test
    fun settingsLayoutAndFormatChoicesReportTheirActualSelection() {
        val layout = mutableStateOf(LibraryView.LIST)
        val format = mutableStateOf(NoteFormatPreference.MARKDOWN)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(destination = LibraryDestination.SETTINGS, libraryView = layout.value, noteFormatPreference = format.value),
            NotesActions(onToggleLibraryView = { layout.value = if (layout.value == LibraryView.LIST) LibraryView.CARDS else LibraryView.LIST },
                onNoteFormatPreferenceChange = { format.value = it })) } }
        compose.onNodeWithTag("settings-open-APPEARANCE").performClick()
        compose.onNodeWithTag("settings-page-APPEARANCE").performScrollToNode(hasTestTag("layout-choice-CARDS"))
        compose.onNodeWithTag("layout-choice-CARDS").performClick().assertIsSelected()
        assertEquals(LibraryView.CARDS, layout.value)
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.onNodeWithTag("settings-open-NOTES").performClick()
        compose.onNodeWithTag("format-choice-TEXT").performClick().assertIsSelected()
        assertEquals(NoteFormatPreference.TEXT, format.value)
    }

    @Test
    fun assignedNotesOfferCategoriesFromTheWholeLibrary() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "category-options-")
        val engine = com.kgs.notes.engine.ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        val source = object : Source {
            override val descriptor = SourceDescriptor(SourceId("category-fixture"), "Category fixture", SourceCapabilities(true, true, true, false, true))
            override val notes = MutableStateFlow(listOf("Server One", "Server Two").mapIndexed { index, category ->
                com.kgs.notes.engine.SourceNote(index.toString(), "r1", "Fixture $index", "Fixture content", category,
                    false, Instant.parse("2026-08-22T09:00:00Z"), false)
            })
            override suspend fun refresh() = Unit
            override suspend fun createNote(note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote = error("Unused")
            override suspend fun updateNote(remoteId: String, expectedRevision: String, note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote = error("Unused")
            override suspend fun deleteNote(remoteId: String, expectedRevision: String) = error("Unused")
        }
        engine.attachSource(source)
        engine.refreshSources()
        val local = engine.createDraft(category = "Local Category")
        engine.updateContent(local, "Local fixture")
        val edited = engine.library.value.active.first { it.category == "Server One" }
        val preferences = application.getSharedPreferences("category-options-${root.fileName}", Application.MODE_PRIVATE)
        val viewModel = NotesViewModel(application, engine, preferences,
            accountStore = NextcloudAccountStore(root.resolve("accounts")))
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesApp(viewModel) } }
        compose.onNodeWithTag("note-card-${edited.id.value}").performClick()
        compose.onNodeWithContentDescription("Choose Category: Server One").performClick()
        compose.onNodeWithTag("single-choice-Server One").assertIsSelected()
        compose.onNodeWithTag("single-choice-Server Two").assertIsDisplayed()
        compose.onNodeWithTag("single-choice-Local Category").assertIsDisplayed()
        Unit
    }

    @Test
    fun changingViewAtTheTopPreservesTheInsetInBothDirections() {
        val notes = (0..12).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Note $it") }
        val view = mutableStateOf(LibraryView.CARDS)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = notes, libraryView = view.value),
                NotesActions(onToggleLibraryView = { view.value = if (view.value == LibraryView.CARDS) LibraryView.LIST else LibraryView.CARDS })) }
        }
        compose.mainClock.advanceTimeBy(1000)
        val tag = "note-card-${notes.first().id.value}"
        val initialTop = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        repeat(4) {
            compose.onNodeWithContentDescription(if (view.value == LibraryView.CARDS) "List view" else "Card view").performClick()
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(initialTop, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }

    @Test
    fun verticalIntentDoesNotArmFavoriteEvenWhenTheFingerAlsoMovesRight() {
        val notes = (0..20).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Scroll $it") }
        var favorites = 0
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = notes),
                    NotesActions(onSelectedNotesAction = { _, _, _ -> favorites++ })) }
            }
        }
        compose.onNodeWithTag("note-swipe-${notes.first().id.value}").performTouchInput {
            down(Offset(width * .1f, height * .8f))
            // Start vertically, then drift far enough right to exceed the Favorite threshold.
            moveTo(Offset(width * .12f, height * .45f), delayMillis = 40)
            moveTo(Offset(width * .9f, -height * 1.5f), delayMillis = 120)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, favorites)
        assertTrue(feedback.isEmpty())
        val scroll = compose.onNodeWithTag("notes-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The vertical gesture must reach the collection", scroll.value() > 0f)
    }

    @Test
    fun swipeClingsBeforeThresholdThenSpringsForwardWhileStillHeld() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Cling and spring")
        var threshold = 0f
        var density = 1f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            threshold = 132f * density
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions()) }
        }
        compose.mainClock.advanceTimeBy(1000)
        val swipe = compose.onNodeWithTag("note-swipe-${note.id.value}")
        val card = compose.onNodeWithTag("note-card-${note.id.value}")
        val beforeBounds = card.fetchSemanticsNode().boundsInRoot
        val before = beforeBounds.left
        val width = swipe.fetchSemanticsNode().boundsInRoot.width
        threshold = minOf(threshold, width * .65f)
        swipe.performTouchInput {
            down(Offset(width * .05f, height / 2f))
            moveTo(Offset(width * .05f + threshold * .9f, height / 2f), delayMillis = 100)
        }
        compose.mainClock.advanceTimeBy(500)
        val clinging = card.fetchSemanticsNode().boundsInRoot.left - before
        assertTrue(clinging > 0f && clinging < threshold * .3f)
        val outline = compose.onRoot().captureToImage().toPixelMap()
        val backgroundX = (beforeBounds.left + 4f * density).toInt()
        val centerY = beforeBounds.center.y.toInt()
        val swipeColor = outline[backgroundX, centerY]
        assertTrue("The Favorite reveal must use a red hue, including in the blue theme",
            swipeColor.red > swipeColor.green && swipeColor.red > swipeColor.blue)
        val iconX = (beforeBounds.left + 24f * density).toInt()
        val iconY = (beforeBounds.center.y - 3f * density).toInt()
        assertEquals(outline[backgroundX, centerY], outline[iconX, iconY])
        swipe.performTouchInput {
            moveTo(Offset(width * .05f + threshold * 1.05f, height / 2f), delayMillis = 100)
        }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(card.fetchSemanticsNode().boundsInRoot.left - before > threshold * .65f)
        val filled = compose.onRoot().captureToImage().toPixelMap()
        assertEquals("The swipe color must remain fixed", outline[backgroundX, centerY], filled[backgroundX, centerY])
        assertNotEquals("The heart must fill at the threshold", outline[iconX, iconY], filled[iconX, iconY])
        swipe.performTouchInput { up() }
    }

    @Test
    fun swipeArmsWhileHeldAndCommitsOnlyOnRelease() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Release to favorite")
        var favorites = 0
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = listOf(note)),
                    NotesActions(onSelectedNotesAction = { _, _, _ -> favorites++ })) }
            }
        }
        val swipe = compose.onNodeWithTag("note-swipe-${note.id.value}")
        val card = compose.onNodeWithTag("note-card-${note.id.value}")
        val before = card.fetchSemanticsNode().boundsInRoot
        swipe.performTouchInput {
            down(Offset(width * .05f, height / 2f))
            moveTo(Offset(width * .9f, height / 2f), delayMillis = 100)
        }
        compose.runOnIdle {
            assertEquals(0, favorites)
            assertEquals(listOf(HapticFeedbackType.LongPress), feedback)
        }
        compose.waitForIdle()
        assertTrue(card.fetchSemanticsNode().boundsInRoot.left > before.left)
        swipe.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, favorites)
        assertEquals(before.left, card.fetchSemanticsNode().boundsInRoot.left, 1f)
    }

    @Test
    fun pullingBackBelowTheThresholdCancelsAnArmedSwipe() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Cancel favorite")
        var favorites = 0
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = listOf(note)),
            NotesActions(onSelectedNotesAction = { _, _, _ -> favorites++ })) } }
        compose.onNodeWithTag("note-swipe-${note.id.value}").performTouchInput {
            down(Offset(width * .05f, height / 2f))
            moveTo(Offset(width * .9f, height / 2f), delayMillis = 100)
            moveTo(Offset(width * .1f, height / 2f), delayMillis = 100)
            up()
        }
        compose.waitForIdle()
        assertEquals(0, favorites)
    }

    @Test
    fun narrowingAScrolledSearchKeepsTheMatchingNoteOnScreenWhileItSettles() {
        val notes = (0..25).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Result $it") }
        val state = mutableStateOf(NotesUiState(notes = notes))
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(state.value, NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("notes-list").performScrollToIndex(10)
        compose.mainClock.advanceTimeByFrame()
        val match = compose.onNodeWithTag("note-card-${notes[10].id.value}")
        val before = match.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(query = "Result 10", notes = listOf(notes[10])) }
        compose.mainClock.advanceTimeBy(64)
        val moving = match.fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(1000)
        val settled = match.fetchSemanticsNode().boundsInRoot
        assertTrue(moving.top >= before.top - 1f && moving.top <= settled.top + 1f)
        assertTrue(moving.height > 0f)
    }

    @Test
    fun rapidSearchReversalsKeepExactlyOneCopyOfEveryVisibleNote() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First match")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second match")
        val state = mutableStateOf(NotesUiState(notes = listOf(first, second)))
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(state.value, NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        repeat(5) {
            compose.runOnIdle { state.value = state.value.copy(query = "Second", notes = listOf(second)) }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("note-card-${first.id.value}").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { state.value = state.value.copy(query = "", notes = listOf(first, second)) }
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("note-card-${first.id.value}").assertCountEquals(1)
            compose.onAllNodesWithTag("note-card-${second.id.value}").assertCountEquals(1)
        }
        compose.mainClock.advanceTimeBy(1000)
    }

    @Test
    fun timestampChangesColorAndRemainsDistinctWhenANoteIsHighlighted() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Timestamp contrast")
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions()) } }
        val timestamp = compose.onNodeWithTag("note-timestamp-${note.id.value}", useUnmergedTree = true)
        val before = timestamp.captureToImage().toPixelMap()
        val beforeColor = before[3, before.height / 2]
        val card = compose.onNodeWithTag("note-card-${note.id.value}")
        card.performTouchInput { longClick() }
        compose.waitForIdle()
        val after = timestamp.captureToImage().toPixelMap()
        val afterColor = after[3, after.height / 2]
        val background = card.captureToImage().toPixelMap()
        assertNotEquals(beforeColor, afterColor)
        val inset = (10 * ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density).toInt()
        assertNotEquals(background[background.width / 2, inset], afterColor)
    }

    @Test
    fun currentCategoryRemainsVisibleAndChoicesUseSingleSelectionSemantics() {
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(NotesUiState(selectedNote = sampleNote(category = "Just created")), NotesActions())
            }
        }
        compose.onNodeWithContentDescription("Choose Category: Just created").performClick()
        compose.onNodeWithTag("single-choice-Just created").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
        compose.onNodeWithTag("single-choice-No Category")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun favoritesAndLegacyPinsFollowTheSortOrderWithoutASeparateSection() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "favorite-order-")
        val engine: NotesEngine = LocalNotesEngine.open(root)
        val ids = runBlocking {
            listOf("A ordinary", "B favorite", "C legacy pin").map { title ->
                engine.createDraft().also { id ->
                    engine.updateContent(id, "Fixture preview")
                    engine.rename(id, title)
                    engine.close(id)
                }
            }.also {
                engine.toggleFavorite(it[1])
                engine.togglePin(it[2])
            }
        }
        val preferences = application.getSharedPreferences("favorite-order-${root.fileName}", Application.MODE_PRIVATE)
        val viewModel = NotesViewModel(application, engine, preferences, NextcloudAccountStore(root.resolve("accounts")))
        viewModel.setSort(LibrarySort.ALPHABETICAL)
        viewModel.toggleSortDirection()
        compose.setContent { KgsNotesApp(viewModel) }
        val bounds = ids.map { compose.onNodeWithTag("note-card-${it.value}").fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds[0].top < bounds[1].top && bounds[1].top < bounds[2].top)
        compose.onNodeWithTag("notes-section-Pinned").assertDoesNotExist()
        compose.onNodeWithTag("notes-section-Notes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Card view").performClick()
        compose.onNodeWithTag("notes-section-Pinned").assertDoesNotExist()
        compose.onNodeWithContentDescription("Favorite notes").performClick()
        compose.onNodeWithTag("note-card-${ids[1].value}").assertIsDisplayed()
        compose.onNodeWithTag("note-card-${ids[0].value}").assertDoesNotExist()
        compose.onNodeWithTag("note-card-${ids[2].value}").assertDoesNotExist()
    }

    @Test
    fun longCardTitlesUseTheFullRightEdgeWithoutEllipsis() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE,
            "A long Note title that should continue all the way to the edge of its card")
        val view = mutableStateOf(LibraryView.LIST)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(note), libraryView = view.value), NotesActions()) } }
        repeat(2) {
            val title = compose.onNodeWithText(note.title, useUnmergedTree = true)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertTrue(!layouts.single().isLineEllipsized(0))
            val card = compose.onNodeWithTag("note-card-${note.id.value}").fetchSemanticsNode().boundsInRoot
            assertEquals(card.right, title.fetchSemanticsNode().boundsInRoot.right, 1f)
            compose.runOnIdle { view.value = LibraryView.CARDS }
        }
    }

    @Test
    fun editorTitleStartsBesideTheBackIconWithoutExtraRowSpacing() {
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(selectedNote = sampleNote(), editorMode = com.kgs.notes.editor.EditorMode.SOURCE), NotesActions()) } }
        val back = compose.onNodeWithContentDescription("Back to Notes").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("editor-title").fetchSemanticsNode().boundsInRoot
        assertTrue(title.left <= back.right)
        assertTrue(title.left > back.center.x)
    }

    @Test
    fun pendingCountIsBoundedAndCenteredInItsBadge() {
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(pendingSyncCount = 1234), NotesActions()) }
        }
        val number = compose.onNodeWithTag("pending-sync-count", useUnmergedTree = true).assertTextContains("9+")
            .fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("pending-sync-badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(badge.center.x, number.center.x, 1f)
        assertEquals(badge.center.y, number.center.y, 1f)
    }

    @Test
    fun pendingSyncIndicatorsFadeBothWaysAndRetainTheirCountUntilTheExitEnds() {
        val pending = sampleSummary(SourceId("fade-fixture"), NoteSyncState.SAVED_LOCALLY, "Fade fixture")
        val state = mutableStateOf(NotesUiState(notes = listOf(pending.copy(syncState = NoteSyncState.SYNCED))))
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme { KgsNotesScreen(state.value, NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("pending-sync-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("note-pending-sync-${pending.id.value}", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(notes = listOf(pending), pendingSyncCount = 8) }
        compose.mainClock.advanceTimeBy(64)
        val tags = listOf("pending-sync-badge", "note-pending-sync-${pending.id.value}")
        fun colors(): List<Color> {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            return tags.map { tag ->
                val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                // Left of the badge's centered text, inside the orange circle.
                pixels[(bounds.left + bounds.width * .22f).toInt(), bounds.center.y.toInt()]
            }
        }
        val entering = colors()
        compose.mainClock.advanceTimeBy(400)
        val visible = colors()
        entering.zip(visible).forEach { (start, end) -> assertNotEquals("Indicator must fade in", start, end) }
        val cardBounds = compose.onNodeWithTag("note-card-${pending.id.value}").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(notes = listOf(pending.copy(syncState = NoteSyncState.SYNCED)), pendingSyncCount = 0) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("pending-sync-count", useUnmergedTree = true).assertTextContains("8")
        val exiting = colors()
        exiting.zip(visible).forEach { (during, full) -> assertNotEquals("Indicator must fade out", full, during) }
        assertEquals(cardBounds, compose.onNodeWithTag("note-card-${pending.id.value}").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(400)
        tags.forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertDoesNotExist() }
    }

    @Test
    fun swipingRightFavoritesOnceWithOneThresholdPulseAndDoesNotOpenTheEditor() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Swipe me")
        val favoriteChanges = mutableListOf<String>()
        val favorite = mutableStateOf(false)
        var opened = false
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) {
                    KgsNotesScreen(NotesUiState(notes = listOf(note.copy(favorite = favorite.value))), NotesActions(
                        onSelect = { opened = true },
                        onSelectedNotesAction = { ids, operation, value ->
                            assertEquals(setOf(note.id), ids)
                            assertEquals(NoteSelectionOperation.FAVORITE, operation)
                            favoriteChanges += value
                            favorite.value = value == "add"
                        },
                    ))
                }
            }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf("add"), favoriteChanges)
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf("add", "remove"), favoriteChanges)
        assertEquals(false, opened)
        assertEquals(listOf(HapticFeedbackType.LongPress, HapticFeedbackType.LongPress), feedback)
    }

    @Test
    fun searchChangesAnimateExistingNotesAndRetainDepartingNotesUntilTheirFadeFinishes() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First result")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second result")
        val state = mutableStateOf(NotesUiState(notes = listOf(first, second)))
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(state.value, NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        val card = compose.onNodeWithTag("note-card-${second.id.value}")
        val before = card.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(query = "Second", notes = listOf(second)) }
        compose.mainClock.advanceTimeBy(80)
        val during = card.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("note-card-${first.id.value}").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        val after = card.fetchSemanticsNode().boundsInRoot
        assertTrue(during.top < before.top && during.top > after.top)
        compose.onNodeWithTag("note-card-${first.id.value}").assertDoesNotExist()
        val viewport = compose.onNodeWithTag("notes-list").fetchSemanticsNode().boundsInRoot
        assertTrue("The resting card must clear the top fade", after.top > viewport.top + 12f)
    }

    @Test
    fun rapidRemovalsRetargetFromTheVisiblePositionAndCloseBothGaps() {
        val notes = (0..3).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Removal $it") }
        val state = mutableStateOf(NotesUiState(notes = notes))
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme { KgsNotesScreen(state.value, NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        val firstTop = compose.onNodeWithTag("note-card-${notes[0].id.value}").fetchSemanticsNode().boundsInRoot.top
        val survivor = compose.onNodeWithTag("note-card-${notes[2].id.value}")
        compose.runOnIdle { state.value = state.value.copy(notes = notes.drop(1)) }
        compose.mainClock.advanceTimeBy(80)
        val moving = survivor.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(notes = notes.drop(2)) }
        compose.mainClock.advanceTimeByFrame()
        val retargeted = survivor.fetchSemanticsNode().boundsInRoot
        assertTrue("A second removal must continue from the visible position, without a jump",
            kotlin.math.abs(retargeted.top - moving.top) < moving.height / 10f)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(firstTop, survivor.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("note-card-${notes[0].id.value}").assertDoesNotExist()
        compose.onNodeWithTag("note-card-${notes[1].id.value}").assertDoesNotExist()
    }

    @Test
    fun scrollingDuringNoteCloseTakesOverImmediatelyAndDoesNotLeaveASpringBehind() {
        val notes = (0..19).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Scroll $it") }
        val returning = notes[1]
        val selected = mutableStateOf<Note?>(null)
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(
                    NotesUiState(notes = notes, selectedNote = selected.value,
                        editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    NotesActions(onSelect = { selected.value = sampleNote().copy(id = returning.id, title = returning.title) },
                        onCloseEditor = { selected.value = null }),
                )
            }
        }
        val card = compose.onNodeWithTag("note-card-${returning.id.value}")
        val resting = card.fetchSemanticsNode().boundsInRoot
        card.performClick()
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back to Notes").performClick()
        compose.mainClock.advanceTimeBy(48)
        val list = compose.onNodeWithTag("notes-list")
        fun scrollBy(delta: Float) {
            list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.mainClock.advanceTimeBy(32)
            compose.onRoot().captureToImage()
        }
        scrollBy(80f)
        assertEquals("The returning card must follow the scroll immediately", resting.top - 80f,
            card.fetchSemanticsNode().boundsInRoot.top, 2f)
        scrollBy(-30f)
        val afterScroll = card.fetchSemanticsNode().boundsInRoot
        assertEquals(resting.top - 50f, afterScroll.top, 2f)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("The card must stay in its slot after scrolling stops", afterScroll.top,
            card.fetchSemanticsNode().boundsInRoot.top, 1f)
        card.performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
    }

    @Test
    fun cardsUseIndependentColumnsAndKeepSortedReadingOrder() {
        val notes = (0..3).map { index ->
            sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Card $index").copy(
                snippet = if (index == 1) "" else "Preview",
                previewMarkdown = if (index == 1) "" else (1..30).joinToString("\n") { "Line $it" },
            )
        }
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(NotesUiState(notes = notes, libraryView = LibraryView.CARDS), NotesActions())
            }
        }
        val bounds = notes.map { compose.onNodeWithTag("note-card-${it.id.value}").fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds[0].left < bounds[1].left)
        assertEquals(bounds[0].left, bounds[2].left, 1f)
        assertEquals(bounds[1].left, bounds[3].left, 1f)
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertTrue(bounds[0].height > bounds[0].width)
        assertTrue(bounds[1].height < bounds[1].width)
        assertTrue(bounds[3].top < bounds[2].top)
        assertTrue(bounds[2].top >= bounds[0].bottom)
        assertTrue(bounds[3].top >= bounds[1].bottom)
    }

    @Test
    fun viewToggleSwitchesBetweenCardsAndList() {
        val view = mutableStateOf(LibraryView.LIST)
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    NotesUiState(libraryView = view.value),
                    NotesActions(onToggleLibraryView = {
                        view.value = if (view.value == LibraryView.LIST) LibraryView.CARDS else LibraryView.LIST
                    }),
                )
            }
        }
        compose.onNodeWithContentDescription("Card view")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "List view"))
            .performClick()
        compose.onNodeWithContentDescription("List view")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Card view"))
            .performClick()
        assertEquals(LibraryView.LIST, view.value)
    }

    @Test
    fun changingViewMovesAndResizesTheSameNoteBeforeReachingItsGridPosition() {
        val notes = listOf("First", "Second").map {
            sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, it)
        }
        val view = mutableStateOf(LibraryView.LIST)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    NotesUiState(notes = notes, libraryView = view.value),
                    NotesActions(onToggleLibraryView = { view.value = LibraryView.CARDS }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val tag = "note-card-${notes[1].id.value}"
        val start = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Card view").performClick()
        compose.mainClock.advanceTimeBy(90)
        val moving = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot }
        compose.mainClock.advanceTimeBy(1000)
        val end = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(end.left > start.left)
        assertTrue(end.width < start.width)
        assertTrue("The card must travel between layouts, rather than crossfade in place", moving.any {
            it.left > start.left + 1f && it.left < end.left - 1f &&
                it.width < start.width - 1f && it.width > end.width + 1f
        })
    }

    @Test
    fun viewSwitchKeepsTheNoteAtTheCurrentScrollPositionVisible() {
        val notes = (0..30).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Note $it") }
        val view = mutableStateOf(LibraryView.LIST)
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    NotesUiState(notes = notes, libraryView = view.value),
                    NotesActions(onToggleLibraryView = {
                        view.value = if (view.value == LibraryView.LIST) LibraryView.CARDS else LibraryView.LIST
                    }),
                )
            }
        }
        compose.onNodeWithTag("notes-list").performScrollToIndex(10)
        compose.onNodeWithContentDescription("Card view").performClick()
        compose.onNodeWithTag("note-card-${notes[10].id.value}").assertIsDisplayed()
        compose.onNodeWithContentDescription("List view").performClick()
        compose.onNodeWithTag("note-card-${notes[10].id.value}").assertIsDisplayed()
    }

    @Test
    fun scrolledViewChangesKeepOneCardAndNormalTitleSize() {
        val notes = (0..30).map { sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Note $it") }
        val view = mutableStateOf(LibraryView.LIST)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(NotesUiState(notes = notes, libraryView = view.value),
                    NotesActions(onToggleLibraryView = {
                        view.value = if (view.value == LibraryView.LIST) LibraryView.CARDS else LibraryView.LIST
                    }))
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("notes-list").performScrollToIndex(10)
        val titleHeight = compose.onNodeWithText("Note 10", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Card view").performClick()
        repeat(24) {
            compose.mainClock.advanceTimeBy(16)
            compose.onAllNodesWithTag("note-card-${notes[10].id.value}").assertCountEquals(1)
            val title = compose.onNodeWithText("Note 10", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The transition must not magnify title glyphs", titleHeight, title.height, 1f)
            compose.onNodeWithContentDescription("New note").assertIsDisplayed()
        }
    }

    @Test
    fun viewButtonIsRightOfSortAndItsPressMovesTheSortNeighbor() {
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(NotesUiState(), NotesActions()) } }
        compose.mainClock.advanceTimeBy(1000)
        val button = compose.onNodeWithContentDescription("Card view")
        val sort = compose.onNodeWithContentDescription("Sort notes")
        val resting = button.fetchSemanticsNode().boundsInRoot
        val neighbor = sort.fetchSemanticsNode().boundsInRoot
        assertTrue(resting.left > neighbor.right)
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(button.fetchSemanticsNode().boundsInRoot.width > resting.width)
        assertTrue(sort.fetchSemanticsNode().boundsInRoot.center.x < neighbor.center.x)
        button.performTouchInput { up() }
    }

    @Test
    fun newCategoryOpensANameFieldWithoutRequiringASearchQuery() {
        var assigned = ""
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(NotesUiState(selectedNote = sampleNote()),
                    NotesActions(onCategoryChange = { assigned = it }))
            }
        }
        compose.onNodeWithContentDescription("Choose Category").performClick()
        compose.onNodeWithText("New Category").assertIsEnabled().performClick()
        compose.onNodeWithTag("new-category-name").performTextInput("Projects/Ideas")
        compose.onNodeWithText("Create").performClick()
        assertEquals("Projects/Ideas", assigned)
    }

    @Test
    fun holdingANoteGivesOneLongPressPulseAndEntersSelection() {
        val feedback = mutableListOf<HapticFeedbackType>()
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Hold me")
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) {
                    KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions())
                }
            }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.LongPress), feedback) }
    }

    @Test
    fun trashUsesANeutralCanvasAndDistinctOpaqueCards() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Deleted note")
        compose.setContent {
            KgsNotesTheme(darkTheme = false) {
                KgsNotesScreen(NotesUiState(destination = LibraryDestination.TRASH, notes = listOf(note)), NotesActions())
            }
        }
        val canvas = compose.onNodeWithTag("library-pane").captureToImage().toPixelMap()
        val card = compose.onNodeWithTag("note-card-${note.id.value}").captureToImage().toPixelMap()
        assertEquals(Color(0xFFE9E6E3), canvas[2, canvas.height / 2])
        assertEquals(Color.White, card[card.width / 2, (15 * ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density).toInt()])
        compose.onNodeWithText("Deleted note").assertIsDisplayed()
    }

    @Test
    fun newNoteDoesNotSwapAFormattedTextPlaceholderForTheRichEditor() {
        val selected = mutableStateOf<Note?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    NotesUiState(selectedNote = selected.value, editorMode = com.kgs.notes.editor.EditorMode.RICH),
                    NotesActions(onCreate = { selected.value = sampleNote().copy(markdown = "Plain paragraph\n\nSecond paragraph") }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("New note").performClick()
        compose.mainClock.advanceTimeBy(90)
        compose.onAllNodesWithText("Plain paragraph\n\nSecond paragraph").assertCountEquals(0)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rich-editor-loading-preview").assertDoesNotExist()
    }

    @Test
    fun sourceTextKeepsItsFinalLineBreaksThroughoutTheOpeningMorph() {
        val note = sampleNote().copy(markdown = List(6) { "A paragraph long enough to wrap across several lines in the editor body." }.joinToString("\n\n"))
        val summary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE).copy(id = note.id, title = note.title)
        val selected = mutableStateOf<Note?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent { KgsNotesTheme { KgsNotesScreen(
            NotesUiState(notes = listOf(summary), selectedNote = selected.value, editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
            NotesActions(onSelect = { selected.value = note })) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("note-card-${note.id.value}").performClick()
        var initial: List<Int>? = null
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("source-editor").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            val breaks = (0 until layout.lineCount).map { layout.getLineEnd(it) }
            if (initial == null) initial = breaks else assertEquals(initial, breaks)
        }
    }

    @Test
    fun openingAndClosingAnExistingNoteMorphsItsCardBounds() {
        val note = sampleNote()
        val summary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE).copy(id = note.id, title = note.title)
        val selected = mutableStateOf<Note?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    NotesUiState(notes = listOf(summary), selectedNote = selected.value,
                        editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    NotesActions(onSelect = { selected.value = note }, onCloseEditor = { selected.value = null }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val card = compose.onNodeWithTag("note-card-${note.id.value}").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("note-card-${note.id.value}").performClick()
        compose.mainClock.advanceTimeBy(90)
        val opening = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(1000)
        val opened = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(opening.height > card.height)
        assertTrue(opening.height < opened.height)
        compose.runOnIdle { selected.value = null }
        compose.mainClock.advanceTimeBy(90)
        val closing = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot
        assertTrue(closing.height < opened.height)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(card, compose.onNodeWithTag("note-card-${note.id.value}").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun emptyLibraryOffersANewNoteAction() {
        var clicked = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
    fun longPressSelectsNotesAndTappingAddsToSelection() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "first")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "second")
        var opened: NoteId? = null
        var actionIds = emptySet<NoteId>()
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(first, second)),
                    actions = NotesActions(
                        onSelect = { opened = it },
                        onSelectedNotesAction = { ids, _, _ -> actionIds = ids },
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-card-first").performTouchInput { longClick() }
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        listOf("Rename", "Favourite", "Category", "Location", "Delete", "Done")
            .forEach { action ->
                compose.onNodeWithContentDescription(action).assertIsDisplayed()
            }
        compose.onNodeWithContentDescription("Pin").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sync").assertDoesNotExist()
        compose.onNodeWithTag("note-card-second").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favourite").performClick()

        assertEquals(setOf(first.id, second.id), actionIds)
        assertEquals(null, opened)
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun selectionBarStaysUntilDoneEvenWhenAnActionRemovesTheNoteFromTheCurrentFilter() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        val visible = mutableStateOf(true)
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = if (visible.value) listOf(note) else emptyList()),
                    actions = NotesActions(onSelectedNotesAction = { _, operation, _ ->
                        if (operation == NoteSelectionOperation.FAVORITE) visible.value = false
                    }),
                )
            }
        }

        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Favourite").performClick()
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun deletingSelectedNotesRequiresConfirmation() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        var deletes = 0
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(note)),
                    actions = NotesActions(onSelectedNotesAction = { _, operation, _ ->
                        if (operation == NoteSelectionOperation.TRASH) deletes += 1
                    }),
                )
            }
        }

        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Move to Trash?").assertIsDisplayed()
        compose.onNodeWithTag("delete-note-title-0").assertTextContains(note.title)
        assertEquals(0, deletes)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Move to Trash").performClick()
        assertEquals(1, deletes)
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun deletingMultipleNotesListsTheirTitles() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First Note")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second Note")
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(state = NotesUiState(notes = listOf(first, second)), actions = NotesActions())
            }
        }

        compose.onNodeWithTag("note-card-${first.id.value}").performTouchInput { longClick() }
        compose.onNodeWithTag("note-card-${second.id.value}").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("2 Notes").assertIsDisplayed()
        compose.onNodeWithTag("delete-note-title-0").assertTextContains("First Note")
        compose.onNodeWithTag("delete-note-title-1").assertTextContains("Second Note")
        compose.onNodeWithTag("confirm-note-delete").performClick()
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun permanentDeletionClosesMultipleNoteSelectionInTrash() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First Note")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second Note")
        var deleted = emptySet<NoteId>()
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(first, second), destination = LibraryDestination.TRASH),
                    actions = NotesActions(onSelectedNotesAction = { ids, operation, _ ->
                        if (operation == NoteSelectionOperation.DELETE) deleted = ids
                    }),
                )
            }
        }
        compose.onNodeWithTag("note-card-${first.id.value}").performTouchInput { longClick() }
        compose.onNodeWithTag("note-card-${second.id.value}").performClick()
        compose.onNodeWithContentDescription("Delete permanently").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithContentDescription("Delete permanently").performClick()
        compose.onNodeWithTag("confirm-note-delete").performClick()
        assertEquals(setOf(first.id, second.id), deleted)
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun renameDialogUsesTheSharedFocusableFieldAndKeepsSelectionOpen() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        var renamed = ""
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(note)),
                    actions = NotesActions(onRenameNote = { _, title -> renamed = title }),
                )
            }
        }

        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.onNodeWithTag("rename-note-field").performTextInput(" updated")
        compose.onNodeWithText("Rename").performClick()
        assertTrue(renamed.contains("updated"))
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
    }

    @Test
    fun editorDeleteRequiresConfirmation() {
        var deleted = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(selectedNote = sampleNote()),
                    actions = NotesActions(onMoveToTrash = { deleted = true }),
                )
            }
        }

        compose.onNodeWithContentDescription("More note actions").performClick()
        compose.onNodeWithTag("dropdown-item-Delete").performClick()
        compose.onNodeWithText("Move to Trash?").assertIsDisplayed()
        assertTrue(!deleted)
        compose.onNodeWithText("Move to Trash").performClick()
        assertTrue(deleted)
    }

    @Test
    fun trashActionsAreEvenAndPermanentDeleteRequiresConfirmation() {
        var deleted = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(
                        selectedNote = sampleNote().copy(state = NoteState.TRASHED),
                        destination = LibraryDestination.TRASH,
                    ),
                    actions = NotesActions(onDeletePermanently = { deleted = true }),
                )
            }
        }

        val restoreHeight = compose.onNodeWithTag("trash-restore-action").fetchSemanticsNode().boundsInRoot.height
        val deleteHeight = compose.onNodeWithTag("trash-delete-action").fetchSemanticsNode().boundsInRoot.height
        assertEquals(restoreHeight, deleteHeight, 1f)
        compose.onNodeWithTag("trash-delete-action").performClick()
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        assertTrue(!deleted)
        compose.onNodeWithTag("confirm-note-delete").performClick()
        assertTrue(deleted)
    }

    @Test
    fun notePreviewUsesTheModeLastChosenForThatNote() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
            .copy(snippet = "bold", previewMarkdown = "**bold**")
        val sourceMode = mutableStateOf(true)
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(
                        notes = listOf(note),
                        previewModes = mapOf(note.id to if (sourceMode.value) {
                            com.kgs.notes.editor.EditorMode.SOURCE
                        } else {
                            com.kgs.notes.editor.EditorMode.RICH
                        }),
                    ),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithText("**bold**").assertIsDisplayed()
        compose.runOnIdle { sourceMode.value = false }
        compose.onNodeWithText("bold").assertIsDisplayed()
    }

    @Test
    fun sourceModePreviewSurvivesClosingAndReopeningTheApp() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = application.cacheDir.resolve("preview-mode-${System.nanoTime()}")
        val preferences = application.getSharedPreferences(
            "preview-mode-${System.nanoTime()}",
            Application.MODE_PRIVATE,
        )
        val engine = LocalNotesEngine.open(root.toPath())
        val id = engine.createDraft()
        engine.updateContent(id, "# Field notes\n**bold**")
        engine.close(id)

        val first = NotesViewModel(application, engine, preferences)
        val activeViewModel = mutableStateOf(first)
        compose.setContent { KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesApp(activeViewModel.value) } }
        first.selectNote(id)
        compose.waitUntil(5_000) { first.state.value.selectedNote?.id == id }
        first.setEditorMode(com.kgs.notes.editor.EditorMode.SOURCE)
        first.closeEditor()
        compose.waitUntil(5_000) { first.state.value.selectedNote == null }
        compose.onNodeWithText("**bold**").assertIsDisplayed()

        val reopened = NotesViewModel(application, engine, preferences)
        compose.runOnIdle { activeViewModel.value = reopened }
        compose.waitUntil(5_000) {
            reopened.state.value.previewModes[id] == com.kgs.notes.editor.EditorMode.SOURCE
        }
        compose.onNodeWithText("**bold**").assertIsDisplayed()
        preferences.edit().clear().commit()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun settingsOpensAStockNextcloudConnectionFlow() {
        val connectionVisible = mutableStateOf(false)
        var submittedServer = ""
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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

        compose.onNodeWithTag("settings-open-SOURCES").performClick()
        compose.onNodeWithContentDescription("Add Nextcloud Source").performClick()
        compose.onNodeWithTag("nextcloud-connection-dialog").assertIsDisplayed()
        compose.onNodeWithTag("nextcloud-address").performTextInput("cloud.example.com")
        compose.onNodeWithText("Continue in browser").performClick()

        assertEquals("cloud.example.com", submittedServer)
    }

    @Test
    fun connectedSourceCanBeRenamedAndRemovedFromSettings() {
        val sourceId = SourceId("nextcloud-home")
        var renamedTo = ""
        var removed = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(
                        destination = LibraryDestination.SETTINGS,
                        sources = listOf(
                            SourceChoice(LocalSourceId, "Local Source", true),
                            SourceChoice(sourceId, "Home", true),
                        ),
                    ),
                    actions = NotesActions(
                        onRenameSource = { _, name -> renamedTo = name },
                        onRemoveSource = { removed = true },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings-open-SOURCES").performClick()
        compose.onNodeWithTag("source-settings-nextcloud-home").performClick()
        compose.onNodeWithTag("source-rename").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Home", renamedTo)

        compose.onNodeWithTag("settings-page-SOURCE_DETAILS").performScrollToNode(hasTestTag("source-remove"))
        compose.onNodeWithTag("source-remove").performClick()
        compose.onNodeWithTag("source-remove-confirm").performClick()
        compose.runOnIdle { assertTrue(removed) }
    }

    @Test
    fun updatingSourceConnectionKeepsTheExistingName() {
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(sourceConnection = SourceConnectionUiState(
                        visible = true,
                        editingSourceId = SourceId("nextcloud-home"),
                        initialName = "Home",
                        initialServerUrl = "https://cloud.invalid/",
                        initialLoginName = "river",
                    )),
                    actions = NotesActions(),
                )
            }
        }

        compose.onNodeWithText("Update Nextcloud Source").assertIsDisplayed()
        compose.onNodeWithTag("nextcloud-source-name").assertTextContains("Home")
        compose.onNodeWithTag("nextcloud-username").assertTextContains("river")
    }

    @Test
    fun newNoteSquircleExpandsTowardTheFullEditorBounds() {
        val selected = mutableStateOf<Note?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
    fun newNoteMorphKeepsItsStartingBoundsOnFirstOpenAndAfterAnExistingNote() {
        val selected = mutableStateOf<Note?>(null)
        val existing = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(notes = listOf(existing), selectedNote = selected.value,
                        editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    actions = NotesActions(
                        onCreate = { selected.value = sampleNote() },
                        onSelect = { selected.value = sampleNote() },
                        onCloseEditor = { selected.value = null },
                    ),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        repeat(2) { attempt ->
            val restingImage = compose.onRoot().captureToImage().toPixelMap()
            val sampleY = restingImage.height / 5
            val resting = restingImage[2, sampleY]
            compose.onNodeWithContentDescription("New note").performClick()
            compose.mainClock.advanceTimeByFrame()
            val opening = compose.onRoot().captureToImage().toPixelMap()[2, sampleY]
            assertEquals("The first morph frame must not replace the whole screen (attempt $attempt)", resting, opening)
            compose.mainClock.advanceTimeBy(1200)
            compose.onNodeWithContentDescription("Back to Notes").performClick()
            compose.mainClock.advanceTimeBy(1200)
            if (attempt == 0) {
                compose.onNodeWithTag("note-card-${existing.id.value}").performClick()
                compose.mainClock.advanceTimeBy(1200)
                compose.onNodeWithContentDescription("Back to Notes").performClick()
                compose.mainClock.advanceTimeBy(1200)
            }
        }
    }

    @Test
    fun appButtonsKeepTapsSilentAndPulseWhenLongPressTooltipAppears() {
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) {
                    KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
                }
            }
        }

        val favorite = compose.onNodeWithContentDescription("Favorite notes")
        favorite.performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(feedback.isEmpty()) }
        favorite.performTouchInput { up() }
        compose.runOnIdle { feedback.clear() }
        favorite.performClick()
        compose.runOnIdle { assertTrue(feedback.isEmpty()) }

        compose.runOnIdle { feedback.clear() }
        compose.onNodeWithContentDescription("Favorite notes").performTouchInput { longClick() }

        compose.runOnIdle {
            assertTrue(feedback.contains(HapticFeedbackType.LongPress))
        }
    }

    @Test
    fun libraryUsesTheSameBlueCanvasAsKgsCalendar() {
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
            }
        }

        val image = compose.onNodeWithTag("library-pane").captureToImage().toPixelMap()

        assertEquals(Color(0xFFEDF6FF), image[2, image.height / 2])
    }

    @Test
    fun modalPopupsAreWhiteWithAppBlueSearchFields() {
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
    fun searchFieldsKeepOrdinaryTapsSilent() {
        val feedback = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                KgsNotesTheme(theme = NotesTheme.BLUE) {
                    KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
                }
            }
        }

        compose.onNodeWithTag("notes-search").performClick()

        compose.runOnIdle {
            assertTrue(feedback.isEmpty())
        }
    }

    @Test
    fun aQuickFilterTapKeepsTheExpressiveGroupMotionVisible() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
                KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesApp(viewModel) }
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
        val local = compose.onNodeWithTag("modal-choice-Local Source").fetchSemanticsNode().boundsInRoot
        val cloud = compose.onNodeWithTag("modal-choice-Home Cloud").fetchSemanticsNode().boundsInRoot
        assertTrue("Source cards need visible spacing", cloud.top > local.bottom)
        val sync = compose.onNodeWithTag("sidebar-sync").fetchSemanticsNode().boundsInRoot
        assertEquals("Sync must have the same gap as neighboring Sources", cloud.top - local.bottom, sync.top - cloud.bottom, 1f)
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun noteCardPressFeedbackStaysInsideItsRoundedShape() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
    fun categoryIsHiddenAndDoesNotMoveTimestampOrItsBacking() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Footer note")
        val state = mutableStateOf(NotesUiState(notes = listOf(note), libraryView = LibraryView.CARDS))
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesScreen(state.value, NotesActions()) }
        }
        val timestamp = compose.onNodeWithTag("note-timestamp-${note.id.value}", useUnmergedTree = true)
        val before = timestamp.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(notes = listOf(note.copy(category = "A long Category name"))) }
        compose.waitForIdle()
        assertEquals(before, timestamp.fetchSemanticsNode().boundsInRoot)
        val card = compose.onNodeWithTag("note-card-${note.id.value}").fetchSemanticsNode().boundsInRoot
        val backing = compose.onNodeWithTag("note-timestamp-backing-${note.id.value}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(card.right, backing.right, 1f)
        assertEquals(card.bottom, backing.bottom, 1f)
        compose.onAllNodesWithText("A long Category name", useUnmergedTree = true).assertCountEquals(0)
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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

        compose.onNodeWithTag("settings-open-PRIVACY").performClick()
        compose.onNodeWithTag("metadata-choice-ASK_EVERY_TIME").assertIsSelected()
        compose.onNodeWithTag("metadata-choice-REMOVE_PRIVATE_METADATA").performClick()

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
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesApp(recreated) }
        }

        compose.onNodeWithTag("settings-open-PRIVACY").performClick()
        compose.onNodeWithTag("metadata-choice-KEEP_ORIGINAL").assertIsSelected()
        preferences.edit().clear().commit()
    }

    @Test
    fun settingsExitKeepsBothDestinationsComposedDuringItsTransition() {
        val destination = mutableStateOf(LibraryDestination.SETTINGS)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(
                    state = NotesUiState(destination = destination.value),
                    actions = NotesActions(onDestinationChange = { destination.value = it }),
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithContentDescription("Back to Notes").performClick()
        compose.mainClock.advanceTimeBy(16)

        compose.onNodeWithTag("settings-open-SOURCES").assertIsDisplayed()
        compose.onNodeWithText("Search notes").assertIsDisplayed()
    }

    @Test
    fun sidebarClosesBeforeTrashNavigationAndBothPagesKeepTheirOwnNotesDuringTransition() {
        val active = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Active fixture")
        val trashed = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Trash fixture")
        val state = mutableStateOf(NotesUiState(notes = listOf(active)))
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(state.value, NotesActions(onDestinationChange = {
                    state.value = state.value.copy(destination = it, notes = listOf(trashed))
                }))
            }
        }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("modal-choice-Trash").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertEquals("Navigation must wait for the sidebar to finish closing", LibraryDestination.NOTES, state.value.destination)
        compose.onNodeWithTag("notes-drawer").assertIsDisplayed()
        repeat(90) {
            if (state.value.destination == LibraryDestination.NOTES) compose.mainClock.advanceTimeByFrame()
        }
        assertEquals(LibraryDestination.TRASH, state.value.destination)
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("notes-drawer").assertDoesNotExist()
        compose.onNodeWithTag("note-card-${active.id.value}").assertIsDisplayed()
        compose.onNodeWithTag("note-card-${trashed.id.value}").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("note-card-${active.id.value}").assertDoesNotExist()
        compose.onNodeWithTag("note-card-${trashed.id.value}").assertIsDisplayed()
    }

    @Test
    fun sidebarCategoriesShowOnlyTheirOwnNotesAndProvideAllAndUncategorizedViews() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = application.cacheDir.resolve("sidebar-categories-${System.nanoTime()}")
        val engine = LocalNotesEngine.open(root.toPath())
        val ids = runBlocking {
            listOf("Work" to "Office fixture", "Work/Ideas" to "Ideas fixture", "Personal" to "Personal fixture", "" to "Uncategorized fixture")
                .map { (category, title) ->
                    engine.createDraft(category = category).also { id ->
                        engine.updateContent(id, "Fixture preview")
                        engine.rename(id, title)
                        engine.close(id)
                    }
                }
        }
        val preferences = application.getSharedPreferences("sidebar-categories-${System.nanoTime()}", Application.MODE_PRIVATE)
        val viewModel = NotesViewModel(application, engine, preferences,
            NextcloudAccountStore(root.resolve("test-accounts").toPath()))
        compose.setContent { KgsNotesApp(viewModel) }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-category-Work").performClick()
        compose.onNodeWithTag("library-category-context").assertTextContains("Work")
        compose.onNodeWithContentDescription("Back to All Notes").assertDoesNotExist()
        compose.onNodeWithText("Category", substring = false).assertDoesNotExist()
        val folder = compose.onNodeWithTag("library-category-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithContentDescription("All notes").fetchSemanticsNode().boundsInRoot
        assertEquals(all.center.x, folder.center.x, 1f)
        compose.onNodeWithTag("note-card-${ids[0].value}").assertIsDisplayed()
        for (id in ids.drop(1)) compose.onNodeWithTag("note-card-${id.value}").assertDoesNotExist()

        compose.onNodeWithContentDescription("Favorite notes").performClick()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-no-category").performClick()
        compose.onNodeWithTag("library-category-context").assertTextContains("No Category")
        compose.onNodeWithTag("note-card-${ids.last().value}").assertIsDisplayed()
        for (id in ids.dropLast(1)) compose.onNodeWithTag("note-card-${id.value}").assertDoesNotExist()

        compose.onNodeWithContentDescription("Back to All Notes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-all-notes").performClick()
        compose.onNodeWithTag("library-category-context").assertDoesNotExist()
        for (id in ids) compose.onNodeWithTag("note-card-${id.value}").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-category-Work/Ideas").performClick()
        compose.onNodeWithTag("library-category-context").assertTextContains("Work/Ideas")
        compose.onNodeWithTag("note-card-${ids[1].value}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-all-notes").performClick()
        for (id in ids) compose.onNodeWithTag("note-card-${id.value}").assertIsDisplayed()

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-category-Work").performClick()
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithContentDescription("Choose Category: Work").assertIsDisplayed()
        preferences.edit().clear().commit()
    }

    @Test
    fun sidebarHasOneContinuousLocationListAndAGeneralSyncActionAboveIt() {
        var refreshes = 0
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(categories = listOf("Work", "Personal")),
            NotesActions(onRefreshSources = { refreshes++ })) } }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Categories").assertDoesNotExist()
        val rows = listOf("sidebar-sync", "sidebar-all-notes", "sidebar-no-category", "sidebar-category-Personal", "sidebar-category-Work")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        rows.zipWithNext().forEach { (first, second) -> assertTrue(first.bottom <= second.top) }
        assertEquals(rows[2].top - rows[1].bottom, rows[3].top - rows[2].bottom, 1f)
        compose.onNodeWithTag("sidebar-sync").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("notes-drawer").assertIsDisplayed()
    }

    @Test
    fun manualSyncIncludesHiddenSourcesAndShowsProgressUntilPendingWritesFinish() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "library-sync-")
        val engine: NotesEngine = com.kgs.notes.engine.ConnectedNotesEngine.open(root.resolve("local"), root.resolve("working"))
        val incoming = object : Source {
            override val descriptor = SourceDescriptor(SourceId("incoming-fixture"), "Incoming fixture", SourceCapabilities(true, true, true, false, true))
            override val notes = MutableStateFlow(emptyList<com.kgs.notes.engine.SourceNote>())
            var refreshes = 0
            override suspend fun refresh() {
                refreshes++
                notes.value = listOf(com.kgs.notes.engine.SourceNote("incoming", "r1", "Incoming fixture", "Remote preview", "", false, Instant.now(), false))
            }
            override suspend fun createNote(note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote = error("Unused")
            override suspend fun updateNote(remoteId: String, expectedRevision: String, note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote = error("Unused")
            override suspend fun deleteNote(remoteId: String, expectedRevision: String) = error("Unused")
        }
        val uploadStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseUpload = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hidden = object : Source {
            override val descriptor = SourceDescriptor(SourceId("hidden-fixture"), "Hidden fixture", SourceCapabilities(true, true, true, false, true))
            override val notes = MutableStateFlow(emptyList<com.kgs.notes.engine.SourceNote>())
            var refreshes = 0
            override suspend fun refresh() { refreshes++ }
            override suspend fun createNote(note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote {
                uploadStarted.complete(Unit)
                releaseUpload.await()
                return com.kgs.notes.engine.SourceNote("uploaded", "r1", note.title, note.markdown, note.category, note.favorite, note.modifiedAt, false)
                    .also { notes.value = listOf(it) }
            }
            override suspend fun updateNote(remoteId: String, expectedRevision: String, note: com.kgs.notes.engine.SourceNoteDraft): com.kgs.notes.engine.SourceNote = error("Unused")
            override suspend fun deleteNote(remoteId: String, expectedRevision: String) = error("Unused")
        }
        engine.attachSource(incoming)
        engine.attachSource(hidden)
        val pending = engine.createDraft(sourceId = hidden.descriptor.id)
        engine.updateContent(pending, "Pending fixture")
        val preferences = application.getSharedPreferences("library-sync-${root.fileName}", Application.MODE_PRIVATE)
        val viewModel = NotesViewModel(application, engine, preferences, NextcloudAccountStore(root.resolve("accounts")))
        viewModel.toggleSourceVisibility(hidden.descriptor.id)
        viewModel.openCategory("Other Category")
        compose.setContent { KgsNotesApp(viewModel) }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("sidebar-sync").performClick()
        compose.waitUntil(5000) { uploadStarted.isCompleted }
        compose.onNodeWithTag("sidebar-sync").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        compose.onNodeWithContentDescription("Syncing all Sources").assertIsDisplayed()
        compose.runOnIdle { viewModel.refreshSources() }
        assertEquals(1, incoming.refreshes)
        assertEquals(1, hidden.refreshes)
        releaseUpload.complete(Unit)
        compose.waitUntil(5000) { !viewModel.state.value.syncingSources }
        compose.onNodeWithTag("sidebar-sync").assertIsEnabled()
        assertEquals(NoteSyncState.SYNCED, engine.note(pending)?.syncState)
        assertEquals("Pending fixture", hidden.notes.value.single().markdown)
        assertTrue(engine.library.value.active.any { it.title == "Incoming fixture" })
    }

    @Test
    fun longCategoryListsScrollWhileTrashAndSettingsRemainAccessible() {
        val state = mutableStateOf(NotesUiState(categories = (0..99).map { "Category ${it.toString().padStart(2, '0')}" }))
        compose.setContent {
            KgsNotesTheme { KgsNotesScreen(state.value, NotesActions(onLibraryCategoryChange = {
                state.value = state.value.copy(libraryCategory = it)
            })) }
        }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("modal-choice-Trash").assertIsDisplayed()
        compose.onNodeWithTag("modal-choice-Settings").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-locations").performScrollToNode(hasTestTag("sidebar-category-Category 99"))
        compose.onNodeWithTag("modal-choice-Trash").assertIsDisplayed()
        compose.onNodeWithTag("modal-choice-Settings").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-category-Category 99").performClick()
        compose.onNodeWithTag("library-category-context").assertTextContains("Category 99")
        compose.onNodeWithText("No Notes in this Category").assertIsDisplayed()
    }

    @Test
    fun closingEditorComposesOneOutgoingEditorAndOneIncomingLibrary() {
        val selected = mutableStateOf<Note?>(sampleNote())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
    fun changingSourceShowsProgressAndKeepsTheEditorInPlace() {
        val uiState = mutableStateOf(
            NotesUiState(
                selectedNote = sampleNote(),
                editorMode = com.kgs.notes.editor.EditorMode.SOURCE,
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KgsNotesTheme(theme = NotesTheme.BLUE) {
                KgsNotesScreen(state = uiState.value, actions = NotesActions())
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val initialBounds = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { uiState.value = uiState.value.copy(changingSource = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Saved locally, syncing").assertIsDisplayed()

        compose.runOnIdle {
            uiState.value = uiState.value.copy(
                changingSource = false,
                selectedNote = uiState.value.selectedNote?.copy(
                    sourceId = SourceId("home-cloud"),
                    syncState = NoteSyncState.SYNCED,
                ),
            )
        }
        compose.mainClock.advanceTimeBy(90)
        compose.onNodeWithContentDescription("Synced").assertIsDisplayed()
        compose.onAllNodesWithTag("note-editor").assertCountEquals(1)
        val finalBounds = compose.onNodeWithTag("note-editor").fetchSemanticsNode().boundsInRoot
        assertEquals(initialBounds.left, finalBounds.left, 1f)
        assertEquals(initialBounds.width, finalBounds.width, 1f)
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
            KgsNotesTheme(theme = NotesTheme.BLUE) {
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
            KgsNotesTheme(theme = NotesTheme.BLUE) { KgsNotesApp(viewModel) }
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

    @Test
    fun selectionMenusExpandInOneSheetAndKeepOtherNotesSelectable() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First Note")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second Note")
        compose.setContent {
            KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(first, second)), NotesActions()) }
        }
        compose.onNodeWithTag("note-card-${first.id.value}").performTouchInput { longClick() }
        val originalTop = compose.onNodeWithTag("note-selection-bar").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Category").performClick()
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onAllNodesWithTag("kgs-sheet-frame").assertCountEquals(1)
        compose.onAllNodesWithTag("kgs-sheet-handle").assertCountEquals(1)
        assertTrue(compose.onNodeWithTag("note-selection-bar").fetchSemanticsNode().boundsInRoot.top < originalTop)
        compose.onNodeWithTag("note-card-${second.id.value}").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun selectionMenuCanToggleCollapseReopenAndFlickAway() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(note), categories = listOf("Work", "Personal")), NotesActions()) }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        val collapsedHeight = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Category").performClick()
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("Category").performClick()
        compose.onNodeWithTag("category-dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Category").performClick()
        compose.onNodeWithTag("kgs-sheet-handle").performTouchInput {
            swipe(center, center + Offset(0f, 40f * density), durationMillis = 700)
        }
        compose.onNodeWithTag("category-dialog").assertDoesNotExist()
        assertEquals(collapsedHeight, compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("kgs-sheet-handle").performTouchInput {
            swipe(center, center - Offset(0f, 40f * density), durationMillis = 700)
        }
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onNodeWithTag("kgs-sheet-handle").performTouchInput {
            swipe(center, center + Offset(0f, 100f * density), durationMillis = 80)
        }
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun renameAvailabilityFadesWhileOtherSelectionActionsReflow() {
        val first = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "First Note")
        val second = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE, "Second Note")
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(first, second)), NotesActions()) } }
        compose.onNodeWithTag("note-card-${first.id.value}").performTouchInput { longClick() }
        val before = compose.onNodeWithContentDescription("Favourite").fetchSemanticsNode().boundsInRoot.center.x
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("note-card-${second.id.value}").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithContentDescription("Rename").assertIsDisplayed()
        val during = compose.onNodeWithContentDescription("Favourite").fetchSemanticsNode().boundsInRoot.center.x
        compose.mainClock.advanceTimeBy(160)
        val nearlyDone = compose.onNodeWithContentDescription("Favourite").fetchSemanticsNode().boundsInRoot.center.x
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Rename").assertDoesNotExist()
        val after = compose.onNodeWithContentDescription("Favourite").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("Neighboring actions must move continuously while Rename disappears", during < before && during > after)
        assertEquals("Removing the last invisible slot must not cause a final position jump", nearlyDone, after, 2f)
    }

    @Test
    fun selectionDeletionUsesACenteredConfirmationWithoutExpandingItsSheet() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        compose.setContent { KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions()) } }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        val height = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithTag("kgs-confirmation-dialog").assertIsDisplayed()
        compose.onNodeWithTag("delete-note-title-0").assertIsDisplayed()
        assertEquals(height, compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("kgs-confirmation-dialog").assertDoesNotExist()
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
    }

    @Test
    fun editorHeaderSheetEndsExactlyAboveTheToolbar() {
        compose.setContent {
            KgsNotesTheme { KgsNotesScreen(NotesUiState(selectedNote = sampleNote()), NotesActions()) }
        }
        compose.onNodeWithContentDescription("More note actions").performClick()
        compose.onNodeWithText("Favourite").assertIsDisplayed()
        val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("kgs-sheet-frame").fetchSemanticsNode().boundsInRoot
        assertEquals(toolbar.top, frame.bottom, 1f)
        compose.onNodeWithContentDescription("Dismiss sheet").performClick()
        compose.onNodeWithTag("kgs-sheet-frame").assertDoesNotExist()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
    }

    @Test
    fun backInTrashClosesSelectionBeforeLeavingTrash() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        var navigated = false
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            KgsNotesTheme {
                KgsNotesScreen(NotesUiState(destination = LibraryDestination.TRASH, notes = listOf(note)),
                    NotesActions(onDestinationChange = { navigated = true }))
            }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithTag("note-selection-bar").assertIsDisplayed()
        compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
        assertTrue(!navigated)
        compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        assertTrue(navigated)
    }

    @Test
    fun selectionSheetResizesAsItsMenuAppearsAndStaysFlushWhenSwitchingMenus() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(NotesUiState(notes = listOf(note), categories = listOf("Work", "Personal", "Travel")), NotesActions())
            }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        val initialTop = compose.onNodeWithTag("note-selection-bar").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Category").performClick()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("The actions must move upward while the menu appears", compose.onNodeWithTag("note-selection-bar").fetchSemanticsNode().boundsInRoot.top < initialTop - 2f)
        compose.mainClock.advanceTimeBy(600)
        val largerHeight = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithContentDescription("Location").performClick()
        repeat(5) {
            compose.mainClock.advanceTimeBy(60)
            val body = compose.onNodeWithTag("kgs-sheet-body").fetchSemanticsNode().boundsInRoot
            val frame = compose.onNodeWithTag("kgs-sheet-frame").fetchSemanticsNode().boundsInRoot
            assertEquals("Menu changes must not leave a bottom gap", frame.bottom, body.bottom, 1f)
            assertTrue("Outgoing and incoming menus must occupy one panel", body.height <= largerHeight + 2f)
        }
    }

    @Test
    fun openingSidebarClosesTheSelectionAndItsExpandedMenu() {
        val note = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE)
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            KgsNotesTheme { KgsNotesScreen(NotesUiState(notes = listOf(note)), NotesActions()) }
        }
        compose.onNodeWithTag("note-card-${note.id.value}").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Category").performClick()
        compose.onNodeWithTag("category-dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithContentDescription("KGS Notes logo").assertIsDisplayed()
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
        compose.onNodeWithTag("category-dialog").assertDoesNotExist()
        compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithTag("note-selection-bar").assertDoesNotExist()
    }

    @Test
    fun existingNoteNavigationFadesThePlusButtonBackInWithoutMorphingIt() {
        val note = sampleNote()
        val summary = sampleSummary(LocalSourceId, NoteSyncState.LOCAL_SOURCE).copy(id = note.id, title = note.title)
        val selected = mutableStateOf<Note?>(null)
        compose.setContent {
            KgsNotesTheme {
                KgsNotesScreen(NotesUiState(notes = listOf(summary), selectedNote = selected.value, editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    NotesActions(onSelect = { selected.value = note }, onCloseEditor = { selected.value = null }))
            }
        }
        val button = compose.onNodeWithTag("new-note-button").fetchSemanticsNode().boundsInRoot
        val sample = androidx.compose.ui.geometry.Offset(button.left + button.width / 4f, button.center.y)
        compose.onNodeWithTag("note-card-${note.id.value}").performClick()
        compose.onNodeWithTag("new-note-button").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected.value = null }
        compose.mainClock.advanceTimeBy(64)
        val appearingBounds = compose.onNodeWithTag("new-note-button").fetchSemanticsNode().boundsInRoot
        assertEquals("The FAB must retain its own shape during existing Note transitions", button.width, appearingBounds.width, 1f)
        val appearing = compose.onRoot().captureToImage().toPixelMap()[sample.x.toInt(), sample.y.toInt()]
        compose.mainClock.advanceTimeBy(600)
        val shown = compose.onRoot().captureToImage().toPixelMap()[sample.x.toInt(), sample.y.toInt()]
        assertTrue("The yellow FAB must appear progressively above the returning Note", appearing.red - appearing.blue > .02f)
        assertTrue("The FAB must fade rather than pop into its final color", shown.red - shown.blue > appearing.red - appearing.blue + .02f)
    }

    @Test
    fun emptyDraftShadowExtendsPastItsMovingShapeDuringTheReturnMorph() {
        val selected = mutableStateOf<Note?>(null)
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            KgsNotesTheme {
                KgsNotesScreen(NotesUiState(selectedNote = selected.value, editorMode = com.kgs.notes.editor.EditorMode.SOURCE),
                    NotesActions(onCreate = { selected.value = sampleNote().copy(title = "", markdown = "") }, onCloseEditor = { selected.value = null }))
            }
        }
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected.value = null }
        compose.mainClock.advanceTimeBy(176)
        val shadow = compose.onNodeWithTag("new-note-shadow").fetchSemanticsNode().boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val y = shadow.center.y.toInt().coerceIn(0, pixels.height - 1)
        val near = pixels[(shadow.left - 4f * density).toInt().coerceIn(0, pixels.width - 1), y]
        val far = pixels[(shadow.left - 26f * density).toInt().coerceIn(0, pixels.width - 1), y]
        assertTrue("The shadow must spread beyond the morphing shape without a rectangular cut-off", near.red < far.red - .008f)
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
        override suspend fun removeSource(id: SourceId) = Unit
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
