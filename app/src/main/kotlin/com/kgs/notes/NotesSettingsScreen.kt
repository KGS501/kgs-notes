package com.kgs.notes

import com.kgs.notes.design.R.string as UiR

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import com.kgs.notes.design.KgsBottomSheet
import com.kgs.notes.design.KgsConfirmationDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgs.notes.design.KgsExpressiveSurface
import com.kgs.notes.design.KgsCornerRadii
import com.kgs.notes.design.kgsSheetRegion
import com.kgs.notes.design.KgsListPosition
import com.kgs.notes.design.KgsSettingsFooter
import com.kgs.notes.design.KgsSettingsHeader
import com.kgs.notes.design.KgsSettingsIcon
import com.kgs.notes.design.KgsSettingsItem
import com.kgs.notes.design.KgsSettingsRadioItem
import com.kgs.notes.design.KgsSettingsContainer
import com.kgs.notes.design.KgsSettingsRowGap
import com.kgs.notes.design.KgsSettingsSegmented
import com.kgs.notes.design.KgsSettingsSwitchItem
import com.kgs.notes.design.KgsSettingsTiles
import com.kgs.notes.design.KgsSheetDialog
import com.kgs.notes.design.KgsTextField
import com.kgs.notes.design.KgsTooltip
import com.kgs.notes.design.LocalKgsAccentColors
import com.kgs.notes.design.LocalKgsDarkTheme
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.NotesTheme
import com.kgs.notes.design.defaultEffectsSpec
import com.kgs.notes.design.defaultSpatialSpec
import com.kgs.notes.design.fastEffectsSpec
import com.kgs.notes.design.fastSpatialSpec
import com.kgs.notes.design.kgsListPosition
import com.kgs.notes.design.notesColorScheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.LocalSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val NotesSponsorUrl = "https://github.com/sponsors/KGS501"
internal const val NotesIssuesUrl = "https://github.com/KGS501/kgs-notes/issues"
internal const val NotesSourceCodeUrl = "https://github.com/KGS501/kgs-notes"
internal const val NotesPrivacyPolicyUrl = "https://github.com/KGS501/kgs-notes/blob/main/index.html"
private const val NotesLicenseId = "KGS Notes"

private enum class SettingsPage(val titleRes: Int) {
    HOME(UiR.ui_settings), SOURCES(UiR.ui_sources), SOURCE_DETAILS(UiR.ui_source),
    NOTES(UiR.ui_notes_editor), APPEARANCE(UiR.ui_appearance), PRIVACY(UiR.ui_photos_privacy),
    ABOUT(UiR.ui_about), OPEN_SOURCE(UiR.ui_open_source_licenses), LICENSE(UiR.ui_license),
    CATEGORIES(UiR.ui_categories),
    LANGUAGE(UiR.ui_language),
    RECOVERY(UiR.ui_recovery),
}

/** The top-level page a nested settings page belongs to. */
private val SettingsPage.parent: SettingsPage?
    get() = when (this) {
        SettingsPage.HOME -> null
        SettingsPage.SOURCE_DETAILS -> SettingsPage.SOURCES
        SettingsPage.OPEN_SOURCE -> SettingsPage.ABOUT
        SettingsPage.LICENSE -> SettingsPage.ABOUT
        else -> SettingsPage.HOME
    }

/** Wide windows show settings as a list and its open page side by side, like Android's own Settings. */
internal val SettingsTwoPaneMinWidth = 720.dp

/** Settings navigation belongs to the UI; preferences and Source operations retain their existing owners. */
@Composable
internal fun NotesSettingsScreen(state: NotesUiState, actions: NotesActions, modifier: Modifier = Modifier) {
    val strings = androidx.compose.ui.platform.LocalResources.current

    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var licenseId by rememberSaveable { mutableStateOf(NotesLicenseId) }
    var forward by remember { mutableStateOf(true) }
    val holder = rememberSaveableStateHolder()
    fun navigate(target: SettingsPage, advancing: Boolean = true) { forward = advancing; page = target }
    fun parentOf(current: SettingsPage): SettingsPage? =
        if (current == SettingsPage.LICENSE && licenseId != NotesLicenseId) SettingsPage.OPEN_SOURCE else current.parent
    fun titleOf(current: SettingsPage) = when (current) {
        SettingsPage.SOURCE_DETAILS -> state.sources.firstOrNull { it.id.value == sourceId }?.name ?: strings.getString(UiR.ui_source)
        SettingsPage.LICENSE -> if (licenseId == NotesLicenseId) strings.getString(current.titleRes) else licenseId
        else -> strings.getString(current.titleRes)
    }
    fun backLabelOf(current: SettingsPage) = when (parentOf(current)) {
        null -> strings.getString(UiR.ui_back_to_notes)
        SettingsPage.SOURCES -> strings.getString(UiR.ui_back_to_sources)
        SettingsPage.ABOUT -> strings.getString(UiR.ui_back_to_about)
        SettingsPage.OPEN_SOURCE -> strings.getString(UiR.ui_back_to_open_source_licenses)
        else -> strings.getString(UiR.ui_back_to_settings)
    }

    val pageContent: @Composable (SettingsPage) -> Unit = { destination ->
        val key = destination.name + when (destination) {
            SettingsPage.SOURCE_DETAILS -> sourceId
            SettingsPage.LICENSE -> licenseId
            else -> ""
        }
        holder.SaveableStateProvider(key) {
            var renameOpen by rememberSaveable { mutableStateOf(false) }
            var removeOpen by rememberSaveable { mutableStateOf(false) }
            var categoryPath by rememberSaveable { mutableStateOf("") }
            var categorySource by rememberSaveable { mutableStateOf("") }
            var categoryRemoving by rememberSaveable { mutableStateOf(false) }
            var creatingCategory by rememberSaveable { mutableStateOf(false) }
            var discardingRecord by rememberSaveable { mutableStateOf<String?>(null) }
            val detailsSource = state.sources.firstOrNull { it.id.value == sourceId }
            val license = if (destination == SettingsPage.LICENSE) {
                licenseFor(licenseId).let { it to rememberLicenseParagraphs(it.asset) }
            } else null
            LazyColumn(
                Modifier.fillMaxSize().testTag("settings-page-${destination.name}"),
                verticalArrangement = Arrangement.spacedBy(KgsSettingsRowGap),
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            ) {
                when (destination) {
                    SettingsPage.HOME -> home(strings, state, actions, selected = null, navigate = ::navigate)
                    SettingsPage.CATEGORIES -> categories(strings, state, state.managingCategories,
                        onCreate = { creatingCategory = true },
                        onRename = { categoryPath = it.path; categorySource = it.sourceId.value; categoryRemoving = false },
                        onRemove = { categoryPath = it.path; categorySource = it.sourceId.value; categoryRemoving = true })
                    SettingsPage.LANGUAGE -> languageSettings(strings)
                    SettingsPage.RECOVERY -> recovery(strings, state, actions, onDiscard = { discardingRecord = it })
                    SettingsPage.SOURCES -> sources(strings, state, actions) { sourceId = it; navigate(SettingsPage.SOURCE_DETAILS) }
                    SettingsPage.SOURCE_DETAILS -> if (detailsSource == null) {
                        item { KgsSettingsFooter(strings.getString(UiR.ui_this_source_is_no_longer_available)) }
                    } else sourceDetails(strings, detailsSource, state.defaultSourceId == detailsSource.id, actions,
                        onRename = { renameOpen = true }, onRemove = { removeOpen = true })
                    SettingsPage.NOTES -> notesAndEditor(strings, state, actions)
                    SettingsPage.APPEARANCE -> appearance(strings, state, actions)
                    SettingsPage.PRIVACY -> privacy(strings, state, actions)
                    SettingsPage.ABOUT -> about(strings, actions,
                        onOpenLicense = { licenseId = NotesLicenseId; navigate(SettingsPage.LICENSE, true) },
                        onOpenLibraries = { navigate(SettingsPage.OPEN_SOURCE, true) })
                    SettingsPage.OPEN_SOURCE -> openSourceLicenses(strings) { licenseId = it; navigate(SettingsPage.LICENSE, true) }
                    SettingsPage.LICENSE -> license?.let { (library, paragraphs) -> licenseText(strings, library, paragraphs) }
                }
            }
            if (destination == SettingsPage.CATEGORIES) {
                val category = state.managedCategories.firstOrNull { it.path == categoryPath && it.sourceId.value == categorySource }
                CategoryDialogs(state, actions, creatingCategory, { creatingCategory = it },
                    renaming = category?.takeUnless { categoryRemoving }, removing = category?.takeIf { categoryRemoving },
                    onDismiss = { categoryPath = "" })
            }
            if (destination == SettingsPage.RECOVERY) {
                val record = state.unreadableRecords.firstOrNull { it.id == discardingRecord }
                KgsSheetDialog(
                    expanded = record != null,
                    onDismissRequest = { discardingRecord = null },
                    icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text(strings.getString(UiR.ui_remove_preserved_files)) },
                    text = { Text(strings.getString(UiR.ui_the_preserved_files_of_this_record_will_be_deleted_from_this_device)) },
                    confirmButton = {
                        TextButton(modifier = Modifier.testTag("recovery-discard-confirm"), onClick = {
                            record?.let { actions.onDiscardUnreadableRecord(it.id) }
                            discardingRecord = null
                        }) { Text(strings.getString(UiR.ui_remove_files), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { discardingRecord = null }) { Text(strings.getString(UiR.ui_cancel)) } },
                )
            }
            if (destination == SettingsPage.SOURCE_DETAILS && detailsSource != null) {
                SourceDialogs(detailsSource, actions, renameOpen, { renameOpen = it }, removeOpen, { removeOpen = it },
                    onRemoved = { navigate(SettingsPage.SOURCES, false) })
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val twoPane = width >= SettingsTwoPaneMinWidth
        // Two panes always show a page beside the list; Sources is the natural first one.
        val detail = if (twoPane && page == SettingsPage.HOME) SettingsPage.SOURCES else page
        fun back() {
            val parent = parentOf(detail)
            when {
                parent == null -> actions.onDestinationChange(LibraryDestination.NOTES)
                twoPane && parent == SettingsPage.HOME -> actions.onDestinationChange(LibraryDestination.NOTES)
                else -> navigate(parent, false)
            }
        }
        BackHandler(enabled = state.destination == LibraryDestination.SETTINGS, onBack = ::back)
        if (twoPane) {
            val topLevel = generateSequence(detail) { parentOf(it)?.takeUnless { parent -> parent == SettingsPage.HOME } }.last()
            Row(Modifier.fillMaxSize().testTag("settings-two-pane"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width((width * .38f).coerceIn(280.dp, 400.dp)).fillMaxHeight()) {
                    SettingsHeader(strings.getString(UiR.ui_settings), strings.getString(UiR.ui_back_to_notes), forward,
                        onBack = { actions.onDestinationChange(LibraryDestination.NOTES) })
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("settings-page-HOME"),
                        verticalArrangement = Arrangement.spacedBy(KgsSettingsRowGap),
                        contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
                    ) {
                        home(strings, state, actions, selected = topLevel) { target, _ ->
                            // Switching between top-level pages is lateral; only nesting moves forward.
                            if (target != topLevel || detail != target) navigate(target, true)
                        }
                    }
                }
                // Sheets from a settings page rise within its column, beside the list.
                val pageRegion = remember { com.kgs.notes.design.KgsSheetRegion() }
                Column(Modifier.weight(1f).fillMaxHeight().kgsSheetRegion(pageRegion)) {
                  CompositionLocalProvider(com.kgs.notes.design.LocalKgsSheetRegion provides pageRegion) {
                    val nested = parentOf(detail) != SettingsPage.HOME
                    SettingsHeader(titleOf(detail), if (nested) backLabelOf(detail) else null, forward, onBack = ::back)
                    SettingsPages(detail, forward, Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth(), pageContent)
                  }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                SettingsHeader(titleOf(page), backLabelOf(page), forward, Modifier.widthIn(max = 720.dp), onBack = ::back)
                SettingsPages(page, forward, Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth(), pageContent)
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, backLabel: String?, forward: Boolean, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val motion = LocalKgsMotion.current
    Row(
        modifier.fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 5.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(backLabel != null, enter = fadeIn(motion.defaultEffectsSpec()) + expandHorizontally(motion.defaultSpatialSpec()),
            exit = fadeOut(motion.fastEffectsSpec()) + shrinkHorizontally(motion.fastSpatialSpec())) {
            var shownLabel by remember { mutableStateOf(backLabel.orEmpty()) }
            if (backLabel != null) shownLabel = backLabel
            KgsTooltip(shownLabel) {
                KgsExpressiveSurface(onClick = onBack, showIndication = false,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = shownLabel }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        }
        // The title travels with its page so the header and content read as one move.
        AnimatedContent(title, transitionSpec = {
            val direction = if (forward) 1 else -1
            (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 10 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 10 } + fadeOut(motion.fastEffectsSpec()))
        }, label = "Settings title", modifier = Modifier.weight(1f).padding(start = if (backLabel == null) 4.dp else 0.dp)) {
            Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsPages(page: SettingsPage, forward: Boolean, modifier: Modifier, content: @Composable (SettingsPage) -> Unit) {
    val motion = LocalKgsMotion.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val direction = if (forward) 1 else -1
            (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 7 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 7 } + fadeOut(motion.fastEffectsSpec()))
        },
        label = "Settings page",
        modifier = modifier.clipToBounds(),
    ) { destination -> content(destination) }
}

/** Rows collected for one group so each can be shaped by its position before entering the list. */
private class SettingsGroupBuilder {
    val rows = mutableListOf<Pair<String, @Composable (KgsListPosition) -> Unit>>()
    fun row(key: String, content: @Composable (KgsListPosition) -> Unit) { rows += key to content }
}

private fun LazyListScope.group(key: String, header: String? = null, footer: String? = null, build: SettingsGroupBuilder.() -> Unit) {
    val rows = SettingsGroupBuilder().apply(build).rows
    if (rows.isEmpty()) return
    if (header != null) item(key = "$key-header") { KgsSettingsHeader(header, Modifier.animateItem()) }
    rows.forEachIndexed { index, (rowKey, content) ->
        item(key = "$key-$rowKey") { Box(Modifier.animateItem()) { content(kgsListPosition(index, rows.size)) } }
    }
    if (footer != null) item(key = "$key-footer") { KgsSettingsFooter(footer, Modifier.animateItem()) }
}

private fun LazyListScope.home(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions, selected: SettingsPage?, navigate: (SettingsPage, Boolean) -> Unit) {
    group("notes", strings.getString(UiR.ui_your_notes)) {
        // Records that could not be loaded stay discoverable until rescued or explicitly removed.
        if (state.unreadableRecords.isNotEmpty()) row("recovery") {
            NavigationRow(strings.getString(UiR.ui_recovery), strings.getString(UiR.ui_value_stored_records_couldn_t_be_read, state.unreadableRecords.size),
                "settings-open-RECOVERY", it, selected = selected == SettingsPage.RECOVERY, onClick = { navigate(SettingsPage.RECOVERY, true) }) {
                KgsSettingsIcon(MaterialTheme.colorScheme.error) { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
            }
        }
        row("sources") { NavigationRow(strings.getString(UiR.ui_sources), state.sources.joinToString { it.name }, "settings-open-SOURCES", it,
            selected = selected == SettingsPage.SOURCES, onClick = { navigate(SettingsPage.SOURCES, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { CloudGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("editor") { NavigationRow(strings.getString(UiR.ui_notes_editor), "${editorModeLabel(strings, state.defaultEditorMode)} · ${strings.getString(state.noteFormatPreference.labelRes)}", "settings-open-NOTES", it,
            selected = selected == SettingsPage.NOTES, onClick = { navigate(SettingsPage.NOTES, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
        row("categories") { NavigationRow(strings.getString(UiR.ui_categories), strings.getString(UiR.ui_create_rename_or_remove_categories), "settings-open-CATEGORIES", it,
            selected = selected == SettingsPage.CATEGORIES, onClick = { navigate(SettingsPage.CATEGORIES, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { CategoryIcon(false, MaterialTheme.colorScheme.secondary) } } }
    }
    group("personal", strings.getString(UiR.ui_make_it_yours)) {
        row("language") { NavigationRow(strings.getString(UiR.ui_language), strings.getString(UiR.ui_system_english_or_german), "settings-open-LANGUAGE", it,
            selected = selected == SettingsPage.LANGUAGE, onClick = { navigate(SettingsPage.LANGUAGE, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) {
                Text("Aa", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleSmall)
            } } }
        row("appearance") { NavigationRow(strings.getString(UiR.ui_appearance), listOfNotNull(darkModeSummary(strings, state.darkMode), strings.getString(state.theme.labelRes), layoutLabel(strings, state.libraryView)).joinToString(" · "), "settings-open-APPEARANCE", it,
            selected = selected == SettingsPage.APPEARANCE, onClick = { navigate(SettingsPage.APPEARANCE, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.tertiary) { AppearanceGlyph(MaterialTheme.colorScheme.tertiary) } } }
        row("privacy") { NavigationRow(strings.getString(UiR.ui_photos_privacy), metadataTitle(strings, state.attachmentMetadataPreference), "settings-open-PRIVACY", it,
            selected = selected == SettingsPage.PRIVACY, onClick = { navigate(SettingsPage.PRIVACY, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { PrivacyGlyph(MaterialTheme.colorScheme.secondary) } } }
    }
    group("project", strings.getString(UiR.ui_project)) {
        row("about") { NavigationRow(strings.getString(UiR.ui_about_kgs_notes), strings.getString(UiR.ui_version_value, BuildConfig.VERSION_NAME), "settings-open-ABOUT", it,
            selected = selected == SettingsPage.ABOUT, onClick = { navigate(SettingsPage.ABOUT, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
        row("sponsor") { NavigationRow(strings.getString(UiR.ui_sponsor_this_project), "GitHub Sponsors", "settings-sponsor", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesSponsorUrl) }) { KgsSettingsIcon(SponsorRed) { Icon(Icons.Default.Favorite, null, tint = SponsorRed, modifier = Modifier.size(20.dp)) } } }
        row("bug") { NavigationRow(strings.getString(UiR.ui_report_a_bug), strings.getString(UiR.ui_notes_issues_on_github), "settings-report-bug", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesIssuesUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.onSurfaceVariant) { BugGlyph(MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}

private fun LazyListScope.sources(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions, onOpen: (String) -> Unit) {
    group("sources", strings.getString(UiR.ui_your_sources)) {
        state.sources.forEach { source ->
            row(source.id.value) {
                val summary = buildList {
                    add(sourceKind(strings, source))
                    if (state.defaultSourceId == source.id) add(strings.getString(UiR.ui_default))
                    if (!source.visible) add(strings.getString(UiR.ui_hidden))
                }.joinToString(" · ")
                NavigationRow(source.name, summary, "source-settings-${source.id.value}", it, onClick = { onOpen(source.id.value) }) {
                    KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { SourceGlyph(source) }
                }
            }
        }
        row("add") {
            KgsSettingsItem(strings.getString(UiR.ui_add_source), it, summary = strings.getString(UiR.ui_connect_nextcloud), onClick = actions.onAddSource, titleColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings-add-source").semantics { contentDescription = strings.getString(UiR.ui_add_nextcloud_source) },
                leading = { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) } })
        }
    }
    // Choosing a default only means something once there is more than one place to save to.
    val choices = state.sources.filter { it.connected || it.id == LocalSourceId }
    if (choices.size > 1) group("default", strings.getString(UiR.ui_save_new_notes_to)) {
        choices.forEach { source ->
            row(source.id.value) {
                KgsSettingsRadioItem(source.name, selected = state.defaultSourceId == source.id, onClick = { actions.onDefaultSourceChange(source.id) },
                    position = it, summary = sourceKind(strings, source), modifier = Modifier.testTag("default-source-${source.id.value}"))
            }
        }
    }
}

private fun LazyListScope.categories(strings: android.content.res.Resources, state: NotesUiState, busy: Boolean,
    onCreate: () -> Unit, onRename: (ManagedCategory) -> Unit, onRemove: (ManagedCategory) -> Unit) {
    group("new") {
        row("new") {
            KgsSettingsItem(strings.getString(UiR.ui_new_category), it, onClick = if (categorySources(state).isEmpty()) null else onCreate,
                titleColor = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("settings-new-category"),
                leading = { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) } })
        }
    }
    if (state.managedCategories.isEmpty()) group("empty-categories") {
        row("empty") { KgsSettingsItem(strings.getString(UiR.ui_no_categories_yet), it,
            summary = strings.getString(UiR.ui_create_one_here_or_choose_a_category_for_a_note)) }
    }
    state.sources.forEach { source ->
        val categories = state.managedCategories.filter { it.sourceId == source.id }
        val editable = source.connected && source.writable && !busy
        if (categories.isNotEmpty()) group("categories-${source.id.value}", source.name,
            footer = if (!source.connected) strings.getString(UiR.ui_reconnect_this_source_to_manage_its_categories)
                else strings.getString(UiR.ui_renaming_includes_subcategories_removing_a_category_keeps_its_notes)) {
            categories.forEach { category -> row(category.path) {
                // Tapping renames; the trailing action removes. Both stay on this page.
                KgsSettingsItem(category.path, it, summary = strings.getQuantityString(com.kgs.notes.design.R.plurals.note_count, category.noteCount, category.noteCount),
                    onClick = if (editable) ({ onRename(category) }) else null,
                    modifier = Modifier.testTag("manage-category-${source.id.value}-${category.path}"),
                    leading = { CategoryIcon(true, MaterialTheme.colorScheme.secondary) },
                    trailing = if (editable) ({
                        val label = strings.getString(UiR.ui_remove_value_2, category.path)
                        KgsTooltip(label) {
                            KgsExpressiveSurface(onClick = { onRemove(category) }, showIndication = false,
                                color = Color.Transparent, restingCorners = KgsCornerRadii(18.dp),
                                modifier = Modifier.size(36.dp).testTag("category-remove-${source.id.value}-${category.path}")
                                    .semantics { contentDescription = label }) {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }) else null)
            } }
        }
    }
}

/** Sources where a person can create a Category: connected, writable, and organized by Categories. */
private fun categorySources(state: NotesUiState): List<SourceChoice> =
    state.sources.filter { it.connected && it.writable && it.categories }

@Composable
private fun CategoryDialogs(state: NotesUiState, actions: NotesActions, creating: Boolean, onCreatingChange: (Boolean) -> Unit,
    renaming: ManagedCategory?, removing: ManagedCategory?, onDismiss: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val choices = categorySources(state)

    var newName by rememberSaveable(creating) { mutableStateOf("") }
    var newSource by rememberSaveable(creating) {
        mutableStateOf((choices.firstOrNull { it.id == state.defaultSourceId } ?: choices.firstOrNull())?.id?.value.orEmpty())
    }
    val createTarget = runCatching { com.kgs.notes.engine.normalizedCategoryPath(newName) }.getOrNull()
    val createSource = choices.firstOrNull { it.id.value == newSource } ?: choices.firstOrNull()
    val exists = state.managedCategories.any { it.sourceId == createSource?.id && it.path.equals(createTarget, ignoreCase = true) }
    KgsSheetDialog(
        expanded = creating,
        onDismissRequest = { onCreatingChange(false) },
        title = { Text(strings.getString(UiR.ui_new_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KgsTextField(newName, { newName = it }, placeholder = strings.getString(UiR.ui_category_name),
                    modifier = Modifier.fillMaxWidth().testTag("new-category-field"))
                Text(strings.getString(UiR.ui_use_for_subcategories_existing_categories_with_the_same_name_are_combined),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (choices.size > 1) {
                    KgsSettingsSegmented(choices, createSource ?: choices.first(), { newSource = it.id.value }, label = { it.name },
                        position = KgsListPosition.ONLY, title = strings.getString(UiR.ui_save_in),
                        segmentModifier = { source -> Modifier.testTag("new-category-source-${source.id.value}") })
                }
                // A Nextcloud Notes Source cannot store a Category without a Note.
                if (createSource != null && createSource.id != LocalSourceId) {
                    Text(strings.getString(UiR.ui_value_keeps_a_category_only_while_a_note_uses_it_until_then_it_stays_on_this_device, createSource.name),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = createTarget != null && createSource != null && !exists, modifier = Modifier.testTag("new-category-confirm"), onClick = {
                createSource?.let { actions.onCreateCategory(it.id, newName) }
                onCreatingChange(false)
            }) { Text(strings.getString(UiR.ui_create)) }
        },
        dismissButton = { TextButton(onClick = { onCreatingChange(false) }) { Text(strings.getString(UiR.ui_cancel)) } },
    )

    // Retain the last category while a dialog animates out.
    var shownRename by remember { mutableStateOf(renaming) }
    if (renaming != null) shownRename = renaming
    var name by rememberSaveable(renaming?.sourceId, renaming?.path) { mutableStateOf(renaming?.path.orEmpty()) }
    val renameTarget = runCatching { com.kgs.notes.engine.normalizedCategoryPath(name) }.getOrNull()
    val renameValid = renameTarget != null && renameTarget != shownRename?.path && !renameTarget.startsWith("${shownRename?.path}/")
    KgsSheetDialog(
        expanded = renaming != null,
        onDismissRequest = onDismiss,
        title = { Text(strings.getString(UiR.ui_rename_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KgsTextField(name, { name = it }, placeholder = strings.getString(UiR.ui_category_name),
                    modifier = Modifier.fillMaxWidth().testTag("rename-category-field"))
                Text(strings.getString(UiR.ui_use_for_subcategories_existing_categories_with_the_same_name_are_combined),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !state.managingCategories && renameValid, modifier = Modifier.testTag("category-rename-confirm"), onClick = {
                shownRename?.let { actions.onRenameCategory(it.sourceId, it.path, renameTarget ?: return@let) }
                onDismiss()
            }) { Text(strings.getString(UiR.ui_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.getString(UiR.ui_cancel)) } },
    )

    var shownRemove by remember { mutableStateOf(removing) }
    if (removing != null) shownRemove = removing
    KgsSheetDialog(
        expanded = removing != null,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(strings.getString(UiR.ui_remove_value_2, shownRemove?.path.orEmpty())) },
        text = { Text(strings.getString(UiR.ui_all_notes_in_this_category_and_its_subcategories_will_be_kept_without_a_category_no_n)) },
        confirmButton = {
            TextButton(enabled = !state.managingCategories, modifier = Modifier.testTag("category-remove-confirm"), onClick = {
                shownRemove?.let { actions.onDeleteCategory(it.sourceId, it.path) }
                onDismiss()
            }) { Text(strings.getString(UiR.ui_remove_category), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.getString(UiR.ui_cancel)) } },
    )
}

private fun LazyListScope.sourceDetails(strings: android.content.res.Resources, source: SourceChoice, isDefault: Boolean, actions: NotesActions, onRename: () -> Unit, onRemove: () -> Unit) {
    val local = source.id == LocalSourceId
    group("status") {
        row("summary") {
            KgsSettingsItem(if (local) strings.getString(UiR.ui_local_source) else strings.getString(UiR.ui_nextcloud_notes_source), it,
                summary = when { local -> strings.getString(UiR.ui_stored_on_this_device); source.connected -> strings.getString(UiR.ui_connected); else -> strings.getString(UiR.ui_disconnected_reconnect_to_sync) },
                leading = { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { SourceGlyph(source) } })
        }
    }
    group("library", strings.getString(UiR.ui_library)) {
        row("visibility") {
            KgsSettingsSwitchItem(strings.getString(UiR.ui_show_in_library), checked = source.visible, onCheckedChange = { actions.onToggleSourceVisibility(source.id) },
                position = it, summary = if (source.visible) strings.getString(UiR.ui_notes_appear_with_your_other_notes) else strings.getString(UiR.ui_notes_stay_on_this_device_out_of_view),
                modifier = Modifier.testTag("source-visibility"))
        }
        if (source.connected) row("default") {
            if (isDefault) {
                KgsSettingsItem(strings.getString(UiR.ui_default_source), it, summary = strings.getString(UiR.ui_new_notes_are_saved_here),
                    trailing = { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
            } else {
                KgsSettingsItem(strings.getString(UiR.ui_make_default_source), it, summary = strings.getString(UiR.ui_save_new_notes_here), onClick = { actions.onDefaultSourceChange(source.id) },
                    modifier = Modifier.testTag("source-make-default"))
            }
        }
    }
    if (!local) group("connection", strings.getString(UiR.ui_connection)) {
        if (source.connected) {
            row("rename") { KgsSettingsItem(strings.getString(UiR.ui_rename_source), it, summary = source.name, onClick = onRename, modifier = Modifier.testTag("source-rename"),
                leading = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
            row("sync") { KgsSettingsItem(strings.getString(UiR.ui_sync_now), it, summary = strings.getString(UiR.ui_refresh_notes_from_the_server), onClick = actions.onRefreshSources,
                modifier = Modifier.testTag("source-sync"), leading = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
        }
        row("connection") {
            KgsSettingsItem(if (source.connected) strings.getString(UiR.ui_update_connection) else strings.getString(UiR.ui_reconnect_source), it, summary = strings.getString(UiR.ui_nextcloud_sign_in),
                onClick = { if (source.connected) actions.onEditSourceConnection(source.id) else actions.onAddSource() },
                modifier = Modifier.testTag("source-connection"), leading = { CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
    if (!local) group("remove", footer = strings.getString(UiR.ui_notes_on_the_server_stay_there)) {
        // An offline rescue that never changes or deletes the server copies.
        row("copy-pending") {
            KgsSettingsItem(strings.getString(UiR.ui_copy_unsynced_notes_to_local_source), it, summary = strings.getString(UiR.ui_works_offline_server_notes_stay_unchanged),
                onClick = { actions.onCopyPendingNotesToLocal(source.id) }, modifier = Modifier.testTag("source-copy-pending"),
                leading = { LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant) })
        }
        row("remove") {
            KgsSettingsItem(strings.getString(UiR.ui_remove_source), it, summary = strings.getString(UiR.ui_remove_from_this_device), onClick = onRemove, titleColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("source-remove"), leading = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

private fun LazyListScope.recovery(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions, onDiscard: (String) -> Unit) {
    if (state.unreadableRecords.isEmpty()) {
        item(key = "recovery-empty") { KgsSettingsFooter(strings.getString(UiR.ui_every_stored_note_could_be_read)) }
        return
    }
    item(key = "recovery-explanation") { KgsSettingsFooter(strings.getString(UiR.ui_these_records_couldn_t_be_read_their_files_were_kept_unchanged)) }
    state.unreadableRecords.forEach { record ->
        group("record-${record.id}", record.title?.takeIf(String::isNotBlank) ?: strings.getString(UiR.ui_untitled_record)) {
            row("reason") {
                KgsSettingsItem(unreadableReasonLabel(strings, record.reason), it, summary = record.detail,
                    modifier = Modifier.testTag("recovery-record-${record.id}"))
            }
            row("rescue") {
                KgsSettingsItem(strings.getString(UiR.ui_save_a_copy_to_the_local_source), it,
                    summary = strings.getString(UiR.ui_the_preserved_files_stay_unchanged), titleColor = MaterialTheme.colorScheme.primary,
                    onClick = { actions.onRescueUnreadableRecord(record.id) }, modifier = Modifier.testTag("recovery-rescue-${record.id}"),
                    leading = { LocalNotesGlyph(MaterialTheme.colorScheme.primary) })
            }
            row("discard") {
                KgsSettingsItem(strings.getString(UiR.ui_remove_preserved_files), it, titleColor = MaterialTheme.colorScheme.error,
                    onClick = { onDiscard(record.id) }, modifier = Modifier.testTag("recovery-discard-${record.id}"),
                    leading = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
}

private fun unreadableReasonLabel(strings: android.content.res.Resources, reason: com.kgs.notes.engine.UnreadableReason): String = when (reason) {
    com.kgs.notes.engine.UnreadableReason.UNSUPPORTED_VERSION -> strings.getString(UiR.ui_saved_by_a_newer_or_unknown_version)
    com.kgs.notes.engine.UnreadableReason.MALFORMED_METADATA -> strings.getString(UiR.ui_its_details_are_damaged)
    com.kgs.notes.engine.UnreadableReason.MISSING_CONTENT -> strings.getString(UiR.ui_its_text_file_is_missing)
    com.kgs.notes.engine.UnreadableReason.INACCESSIBLE -> strings.getString(UiR.ui_its_files_couldn_t_be_opened)
}

private fun LazyListScope.notesAndEditor(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions) {
    group("editor", strings.getString(UiR.ui_editor), footer = strings.getString(UiR.ui_notes_you_switch_from_the_editor_toolbar_keep_their_own_mode)) {
        row("mode") {
            KgsSettingsSegmented(EditorMode.entries, state.defaultEditorMode, actions.onDefaultEditorModeChange, label = { editorModeLabel(strings, it) },
                position = it, title = strings.getString(UiR.ui_open_notes_in), segmentModifier = { mode -> Modifier.testTag("editor-mode-${mode.name}") })
        }
    }
    group("format", strings.getString(UiR.ui_nextcloud_file_format), footer = strings.getString(UiR.ui_local_source_always_uses_markdown)) {
        NoteFormatPreference.entries.forEach { option ->
            row(option.name) {
                KgsSettingsRadioItem(strings.getString(option.labelRes), selected = state.noteFormatPreference == option, onClick = { actions.onNoteFormatPreferenceChange(option) },
                    position = it, modifier = Modifier.testTag("format-choice-${option.name}"), summary = when (option) {
                        NoteFormatPreference.MARKDOWN -> strings.getString(UiR.ui_keeps_headings_lists_and_links)
                        NoteFormatPreference.TEXT -> strings.getString(UiR.ui_plain_text_files)
                        NoteFormatPreference.FOLLOW_SOURCE -> strings.getString(UiR.ui_use_the_server_s_default)
                    })
            }
        }
    }
}

private fun LazyListScope.appearance(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions) {
    group("theme", strings.getString(UiR.ui_theme)) {
        row("mode") {
            KgsSettingsSegmented(DarkModePreference.entries, state.darkMode, actions.onDarkModeChange, label = { mode -> strings.getString(mode.labelRes) },
                position = it, segmentModifier = { mode -> Modifier.testTag("dark-mode-${mode.name}") })
        }
        row("tiles") {
            KgsSettingsTiles(NotesTheme.entries, state.theme, actions.onThemeChange, label = { theme -> strings.getString(theme.labelRes) }, position = it,
                tileModifier = { theme -> Modifier.testTag("theme-choice-${theme.name}") }) { theme -> ThemePreview(theme) }
        }
    }
    group("library", strings.getString(UiR.ui_library)) {
        row("layout") {
            KgsSettingsTiles(LibraryView.entries, state.libraryView, { view -> if (state.libraryView != view) actions.onToggleLibraryView() },
                label = { layoutLabel(strings, it) }, position = it, tileModifier = { view -> Modifier.testTag("layout-choice-${view.name}") }) { view -> LayoutPreview(view) }
        }
        row("grouping") {
            KgsSettingsSwitchItem(strings.getString(UiR.ui_group_notes), checked = state.groupByDate, onCheckedChange = actions.onGroupByDateChange, position = it,
                summary = strings.getString(UiR.ui_date_sections_or_letters_following_your_sort_order), modifier = Modifier.testTag("group-by-date"))
        }
    }
    group("large-screen", strings.getString(UiR.ui_large_screens), footer = strings.getString(UiR.ui_automatic_adapts_to_tablets_and_folds_expand_a_note_from_its_header_at_any_time)) {
        EditorLayoutPreference.entries.forEach { layout -> row(layout.name) {
            KgsSettingsRadioItem(when (layout) {
                EditorLayoutPreference.AUTOMATIC -> strings.getString(UiR.ui_automatic)
                EditorLayoutPreference.SPLIT -> strings.getString(UiR.ui_library_and_note)
                EditorLayoutPreference.FULL_SCREEN -> strings.getString(UiR.ui_full_screen_notes)
            }, selected = state.editorLayout == layout, onClick = { actions.onEditorLayoutChange(layout) },
                position = it, modifier = Modifier.testTag("editor-layout-${layout.name}"))
        } }
    }
}

private fun LazyListScope.languageSettings(strings: android.content.res.Resources, ) {
    group("language", strings.getString(UiR.ui_app_language)) {
        listOf("", "en", "de").forEach { tag -> row("language-$tag") {
            val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
            KgsSettingsRadioItem(when (tag) { "de" -> "Deutsch"; "en" -> "English"; else -> strings.getString(UiR.ui_system_language) },
                selected = current == tag,
                onClick = { androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag)) },
                position = it, modifier = Modifier.testTag("app-language-${tag.ifEmpty { "system" }}"))
        } }
    }
}

private fun LazyListScope.privacy(strings: android.content.res.Resources, state: NotesUiState, actions: NotesActions) {
    group("metadata", strings.getString(UiR.ui_when_adding_photos), footer = strings.getString(UiR.ui_private_metadata_can_include_location_and_camera_details)) {
        AttachmentMetadataPreference.entries.forEach { option ->
            row(option.name) {
                KgsSettingsRadioItem(metadataTitle(strings, option), selected = state.attachmentMetadataPreference == option,
                    onClick = { actions.onAttachmentMetadataPreferenceChange(option) }, position = it, summary = metadataSummary(strings, option),
                    modifier = Modifier.testTag("metadata-choice-${option.name}"))
            }
        }
    }
}

private fun LazyListScope.about(strings: android.content.res.Resources, actions: NotesActions, onOpenLicense: () -> Unit, onOpenLibraries: () -> Unit) {
    group("app") { row("identity") { AboutIdentity(it) } }
    group("project", footer = strings.getString(UiR.ui_2026_kgs501_kgs_notes_is_free_software_you_can_redistribute_and_modify_it_under_the_t) +
        strings.getString(UiR.ui_of_the_gnu_general_public_license_version_3_or_any_later_version_it_comes_with_no_war)) {
        row("source") { NavigationRow(strings.getString(UiR.ui_source_code), "github.com/KGS501/kgs-notes", "about-source-code", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesSourceCodeUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { CodeGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("privacy") { NavigationRow(strings.getString(UiR.ui_privacy_policy), "github.com/KGS501/kgs-notes", "about-privacy-policy", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesPrivacyPolicyUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { PrivacyGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("license") { NavigationRow(strings.getString(UiR.ui_license), "GPL-3.0-or-later", "about-license", it,
            onClick = onOpenLicense) { KgsSettingsIcon(MaterialTheme.colorScheme.tertiary) { LicenseGlyph(MaterialTheme.colorScheme.tertiary) } } }
        row("libraries") { NavigationRow(strings.getString(UiR.ui_open_source_licenses), strings.getString(UiR.ui_libraries_kgs_notes_is_built_with), "about-open-source-licenses", it,
            onClick = onOpenLibraries) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
    }
}

/**
 * Software shipped in the app and the bundled text of its license, so notices are readable offline.
 * Editor notices are generated by tools/generate_open_source_notices.py.
 */
private class OpenSourceLibrary(val name: String, val purposeRes: Int, val license: String, val asset: String, val notice: String? = null)

private val NotesLicense = OpenSourceLibrary(NotesLicenseId, UiR.ui_note_taking_app, "GPL-3.0-or-later", "gpl-3.0.txt", "© 2026 KGS501")

private val AppLibraries = listOf(
    OpenSourceLibrary("Android Jetpack", UiR.ui_compose_material_3_activity_lifecycle_webkit_and_more, "Apache-2.0", "apache-2.0.txt",
        "Copyright The Android Open Source Project"),
    OpenSourceLibrary("Kotlin", UiR.ui_standard_library_coroutines_serialization_and_annotations, "Apache-2.0", "apache-2.0.txt",
        "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
    OpenSourceLibrary("OkHttp & Okio", UiR.ui_networking, "Apache-2.0", "apache-2.0.txt", "Copyright Square, Inc."),
    OpenSourceLibrary("Guava & JSpecify", UiR.ui_support_annotations_and_futures, "Apache-2.0", "apache-2.0.txt",
        "Copyright The Guava Authors\nCopyright The JSpecify Authors"),
)

private val EditorLibraries = listOf(
    OpenSourceLibrary("Tiptap", UiR.ui_rich_mode_editor, "MIT", "editor-tiptap.txt"),
    OpenSourceLibrary("ProseMirror", UiR.ui_editing_toolkit, "MIT", "editor-prosemirror.txt"),
    OpenSourceLibrary("Marked", UiR.ui_markdown_parsing, "MIT", "editor-marked.txt"),
    OpenSourceLibrary("Linkify", UiR.ui_link_detection, "MIT", "editor-linkify.txt"),
)

private fun licenseFor(id: String): OpenSourceLibrary =
    (AppLibraries + EditorLibraries).firstOrNull { it.name == id } ?: NotesLicense

private fun LazyListScope.openSourceLicenses(strings: android.content.res.Resources, onOpen: (String) -> Unit) {
    listOf("app" to AppLibraries, "editor" to EditorLibraries).forEach { (key, libraries) ->
        group(key, if (key == "app") strings.getString(UiR.ui_app) else strings.getString(UiR.ui_editor)) {
            libraries.forEach { library ->
                row(library.name) {
                    KgsSettingsItem(library.name, it, summary = "${strings.getString(library.purposeRes)} · ${library.license}",
                        onClick = { onOpen(library.name) }, modifier = Modifier.testTag("library-${library.name}"),
                        trailing = { ArrowGlyph(false, MaterialTheme.colorScheme.onSurfaceVariant) })
                }
            }
        }
    }
}

/** License text reads as a document: paragraphs on the page background rather than in cards. */
private fun LazyListScope.licenseText(strings: android.content.res.Resources, library: OpenSourceLibrary, paragraphs: List<String>) {
    group("summary", footer = library.notice) {
        row("license") { KgsSettingsItem(library.license, it, summary = strings.getString(library.purposeRes)) }
    }
    item(key = "spacer") { Box(Modifier.size(12.dp)) }
    paragraphs.forEachIndexed { index, paragraph ->
        item(key = "paragraph-$index") {
            Text(paragraph, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun rememberLicenseParagraphs(asset: String): List<String> {
    val context = LocalContext.current
    val paragraphs by produceState(emptyList<String>(), asset) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("licenses/$asset").bufferedReader().use { it.readText() }
        }.let(::licenseParagraphs)
    }
    return paragraphs
}

/** Rejoins hard-wrapped prose so it fits the screen, while keeping short lines such as package lists intact. */
private fun licenseParagraphs(text: String): List<String> = text.split(Regex("\n\\s*\n"))
    .map(String::trimEnd)
    .filter(String::isNotBlank)
    .map { paragraph ->
        val lines = paragraph.lines()
        if (lines.any { it.length >= 60 }) lines.joinToString(" ") { it.trim() } else lines.joinToString("\n") { it.trim() }
    }

@Composable
private fun AboutIdentity(position: KgsListPosition) {
    val strings = androidx.compose.ui.platform.LocalResources.current

    KgsSettingsContainer(position) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The mark is drawn white; the brand accent tints it and follows the chosen theme.
            Image(painterResource(R.drawable.kgs_logo), contentDescription = null,
                colorFilter = ColorFilter.tint(LocalKgsAccentColors.current.focus),
                modifier = Modifier.size(64.dp).padding(bottom = 8.dp))
            Text("KGS Notes", style = MaterialTheme.typography.titleLarge)
            Text(strings.getString(UiR.ui_version_value, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("about-version"))
        }
    }
}

@Composable
private fun NavigationRow(title: String, summary: String, tag: String, position: KgsListPosition, onClick: () -> Unit,
    external: Boolean = false, selected: Boolean = false, icon: @Composable () -> Unit) {
    KgsSettingsItem(title, position, summary = summary, onClick = onClick, modifier = Modifier.testTag(tag),
        leading = icon, trailing = { ArrowGlyph(external, MaterialTheme.colorScheme.onSurfaceVariant) }, selected = selected)
}

@Composable
private fun SourceDialogs(source: SourceChoice, actions: NotesActions, renameOpen: Boolean, onRenameOpen: (Boolean) -> Unit,
    removeOpen: Boolean, onRemoveOpen: (Boolean) -> Unit, onRemoved: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current

    var removalRequested by remember { mutableStateOf(false) }
    var name by rememberSaveable(source.name) { mutableStateOf(source.name) }
    KgsSheetDialog(
        expanded = renameOpen,
        onDismissRequest = { onRenameOpen(false) },
        title = { Text(strings.getString(UiR.ui_rename_source)) },
        text = { KgsTextField(name, { name = it }, modifier = Modifier.testTag("rename-source-field")) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                actions.onRenameSource(source.id, name)
                onRenameOpen(false)
            }) { Text(strings.getString(UiR.ui_save)) }
        },
        dismissButton = { TextButton(onClick = { onRenameOpen(false) }) { Text(strings.getString(UiR.ui_cancel)) } },
    )
    KgsSheetDialog(
        expanded = removeOpen,
        onDismissRequest = { onRemoveOpen(false) },
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(strings.getString(UiR.ui_remove_value, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.getString(UiR.ui_removes_this_source_and_its_working_copies_from_this_device_notes_on_the_server_stay))
                Text(strings.getString(UiR.ui_sync_pending_edits_first_or_copy_them_to_the_local_source), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("source-remove-confirm"), onClick = {
                onRemoveOpen(false)
                removalRequested = true
            }) { Text(strings.getString(UiR.ui_remove_source), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { onRemoveOpen(false) }) { Text(strings.getString(UiR.ui_cancel)) } },
        onHidden = {
            if (removalRequested) {
                removalRequested = false
                actions.onRemoveSource(source.id)
                onRemoved()
            }
        },
    )
}

@Composable
private fun SourceGlyph(source: SourceChoice) {
    if (source.id == LocalSourceId) LocalNotesGlyph(MaterialTheme.colorScheme.secondary) else CloudGlyph(MaterialTheme.colorScheme.secondary)
}

private val SponsorRed = Color(0xFFD13B4B)

private fun sourceKind(strings: android.content.res.Resources, source: SourceChoice): String = when {
    source.id == LocalSourceId -> strings.getString(UiR.ui_on_this_device)
    !source.connected -> strings.getString(UiR.ui_disconnected)
    else -> "Nextcloud"
}

private fun editorModeLabel(strings: android.content.res.Resources, mode: EditorMode): String = when (mode) {
    EditorMode.RICH -> strings.getString(UiR.ui_rich_mode)
    EditorMode.SOURCE -> strings.getString(UiR.ui_source_mode)
}

/** Only an override is worth mentioning; following the system is the expected default. */
private fun darkModeSummary(strings: android.content.res.Resources, mode: DarkModePreference): String? = strings.getString(mode.labelRes).takeIf { mode != DarkModePreference.SYSTEM }

private fun layoutLabel(strings: android.content.res.Resources, view: LibraryView): String = if (view == LibraryView.CARDS) strings.getString(UiR.ui_cards) else strings.getString(UiR.ui_list)

private fun metadataTitle(strings: android.content.res.Resources, option: AttachmentMetadataPreference): String = when (option) {
    AttachmentMetadataPreference.ASK_EVERY_TIME -> strings.getString(UiR.ui_ask_every_time)
    AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> strings.getString(UiR.ui_remove_private_metadata)
    AttachmentMetadataPreference.KEEP_ORIGINAL -> strings.getString(UiR.ui_keep_original)
}

private fun metadataSummary(strings: android.content.res.Resources, option: AttachmentMetadataPreference): String = when (option) {
    AttachmentMetadataPreference.ASK_EVERY_TIME -> strings.getString(UiR.ui_choose_before_adding_each_photo)
    AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> strings.getString(UiR.ui_strip_location_camera_details)
    AttachmentMetadataPreference.KEEP_ORIGINAL -> strings.getString(UiR.ui_keep_photo_data_intact)
}

@Composable
private fun ThemePreview(theme: NotesTheme) {
    val palette = notesColorScheme(theme, LocalKgsDarkTheme.current)
    val accent = if (theme == NotesTheme.KGS_NOTES) Color(0xFFFFC217) else palette.primaryContainer
    Canvas(Modifier.size(width = 66.dp, height = 54.dp)) { preview {
        drawRoundRect(palette.background, Offset(7f, 4f), Size(74f, 64f), CornerRadius(10f))
        drawRoundRect(palette.surface, Offset(13f, 10f), Size(62f, 9f), CornerRadius(4f))
        repeat(3) { index ->
            drawRoundRect(palette.surface, Offset(13f, 24f + index * 12f), Size(62f, 9f), CornerRadius(3f))
            drawLine(palette.onSurfaceVariant.copy(alpha = .5f), Offset(18f, 28.5f + index * 12f), Offset(42f + index * 4f, 28.5f + index * 12f), 1.5f, StrokeCap.Round)
        }
        drawRoundRect(accent, Offset(60f, 48f), Size(16f, 16f), CornerRadius(4f))
        drawLine(palette.onPrimaryContainer, Offset(65f, 56f), Offset(71f, 56f), 1.5f, StrokeCap.Round)
        drawLine(palette.onPrimaryContainer, Offset(68f, 53f), Offset(68f, 59f), 1.5f, StrokeCap.Round)
    } }
}

@Composable
private fun LayoutPreview(view: LibraryView) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(width = 66.dp, height = 54.dp)) { preview {
        if (view == LibraryView.LIST) repeat(3) { i ->
            drawRoundRect(colors.surface, Offset(8f, 6f + i * 21f), Size(72f, 17f), CornerRadius(4f))
            drawLine(colors.onSurfaceVariant, Offset(14f, 14.5f + i * 21f), Offset(48f, 14.5f + i * 21f), 2f, StrokeCap.Round)
        } else repeat(4) { i ->
            val x = 8f + (i % 2) * 37f; val y = 6f + (i / 2) * 31f
            drawRoundRect(colors.surface, Offset(x, y), Size(35f, 29f), CornerRadius(4f))
            drawLine(colors.onSurfaceVariant, Offset(x + 6f, y + 8f), Offset(x + 25f, y + 8f), 2f, StrokeCap.Round)
            drawLine(colors.outline, Offset(x + 6f, y + 15f), Offset(x + 28f, y + 15f), 1.5f, StrokeCap.Round)
        }
    } }
}

private fun DrawScope.preview(block: DrawScope.() -> Unit) {
    scale(size.width / 88f, size.height / 72f, pivot = Offset.Zero, block = block)
}

@Composable
private fun ArrowGlyph(external: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val p = size.minDimension
        if (external) {
            drawLine(color, Offset(p * .25f, p * .75f), Offset(p * .75f, p * .25f), p * .1f, StrokeCap.Round)
            drawLine(color, Offset(p * .35f, p * .25f), Offset(p * .75f, p * .25f), p * .1f, StrokeCap.Round)
            drawLine(color, Offset(p * .75f, p * .25f), Offset(p * .75f, p * .65f), p * .1f, StrokeCap.Round)
        } else {
            drawLine(color, Offset(p * .4f, p * .25f), Offset(p * .65f, p * .5f), p * .12f, StrokeCap.Round)
            drawLine(color, Offset(p * .65f, p * .5f), Offset(p * .4f, p * .75f), p * .12f, StrokeCap.Round)
        }
    }
}

@Composable
private fun AppearanceGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val p = size.minDimension
        drawCircle(color, p * .38f, center, style = Stroke(p * .09f))
        drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(p * .12f, p * .12f), size = Size(p * .76f, p * .76f))
    }
}

@Composable
private fun CodeGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val p = size.minDimension
        val stroke = p * .09f
        drawLine(color, Offset(p * .32f, p * .28f), Offset(p * .1f, p * .5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(p * .1f, p * .5f), Offset(p * .32f, p * .72f), stroke, StrokeCap.Round)
        drawLine(color, Offset(p * .68f, p * .28f), Offset(p * .9f, p * .5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(p * .9f, p * .5f), Offset(p * .68f, p * .72f), stroke, StrokeCap.Round)
        drawLine(color, Offset(p * .57f, p * .18f), Offset(p * .43f, p * .82f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun LicenseGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val p = size.minDimension
        val stroke = p * .09f
        drawRoundRect(color, Offset(p * .2f, p * .1f), Size(p * .6f, p * .8f), CornerRadius(p * .1f), style = Stroke(stroke))
        repeat(3) { i -> drawLine(color, Offset(p * .33f, p * (.32f + i * .17f)), Offset(p * (.67f - i * .08f), p * (.32f + i * .17f)), stroke * .8f, StrokeCap.Round) }
    }
}

@Composable
private fun BugGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val p = size.minDimension
        drawRoundRect(color, Offset(p * .3f, p * .28f), Size(p * .4f, p * .5f), CornerRadius(p * .15f), style = Stroke(p * .075f))
        repeat(3) { i ->
            val y = p * (.35f + i * .16f)
            drawLine(color, Offset(p * .12f, y), Offset(p * .3f, y), p * .075f, StrokeCap.Round)
            drawLine(color, Offset(p * .7f, y), Offset(p * .88f, y), p * .075f, StrokeCap.Round)
        }
        drawLine(color, Offset(p * .4f, p * .28f), Offset(p * .3f, p * .12f), p * .075f, StrokeCap.Round)
        drawLine(color, Offset(p * .6f, p * .28f), Offset(p * .7f, p * .12f), p * .075f, StrokeCap.Round)
    }
}
