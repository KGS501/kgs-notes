package com.kgs.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

private enum class SettingsPage(val title: String) {
    HOME("Settings"), SOURCES("Sources"), SOURCE_DETAILS("Source"),
    NOTES("Notes & editor"), APPEARANCE("Appearance"), PRIVACY("Photos & privacy"),
    ABOUT("About"), OPEN_SOURCE("Open-source licenses"), LICENSE("License"),
}

/** Settings navigation belongs to the UI; preferences and Source operations retain their existing owners. */
@Composable
internal fun NotesSettingsScreen(state: NotesUiState, actions: NotesActions, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var licenseId by rememberSaveable { mutableStateOf(NotesLicenseId) }
    var forward by remember { mutableStateOf(true) }
    val holder = rememberSaveableStateHolder()
    val motion = LocalKgsMotion.current
    fun navigate(target: SettingsPage, advancing: Boolean = true) { forward = advancing; page = target }
    fun back() {
        when (page) {
            SettingsPage.HOME -> actions.onDestinationChange(LibraryDestination.NOTES)
            SettingsPage.SOURCE_DETAILS -> navigate(SettingsPage.SOURCES, false)
            SettingsPage.OPEN_SOURCE -> navigate(SettingsPage.ABOUT, false)
            SettingsPage.LICENSE -> navigate(if (licenseId == NotesLicenseId) SettingsPage.ABOUT else SettingsPage.OPEN_SOURCE, false)
            else -> navigate(SettingsPage.HOME, false)
        }
    }
    BackHandler(enabled = state.destination == LibraryDestination.SETTINGS, onBack = ::back)
    val title = when (page) {
        SettingsPage.SOURCE_DETAILS -> state.sources.firstOrNull { it.id.value == sourceId }?.name ?: "Source"
        SettingsPage.LICENSE -> if (licenseId == NotesLicenseId) page.title else licenseId
        else -> page.title
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 5.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val backLabel = when (page) {
                SettingsPage.HOME -> "Back to Notes"
                SettingsPage.SOURCE_DETAILS -> "Back to Sources"
                SettingsPage.OPEN_SOURCE -> "Back to About"
                SettingsPage.LICENSE -> if (licenseId == NotesLicenseId) "Back to About" else "Back to Open-source licenses"
                else -> "Back to Settings"
            }
            KgsTooltip(backLabel) {
                KgsExpressiveSurface(onClick = ::back, showIndication = false,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = backLabel }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
            // The title travels with its page so the header and content read as one move.
            AnimatedContent(title, transitionSpec = {
                val direction = if (forward) 1 else -1
                (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 10 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 10 } + fadeOut(motion.fastEffectsSpec()))
            }, label = "Settings title", modifier = Modifier.weight(1f)) { Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val direction = if (forward) 1 else -1
                (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 7 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                    (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 7 } + fadeOut(motion.fastEffectsSpec()))
            },
            label = "Settings page",
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
        ) { destination ->
            val key = destination.name + when (destination) {
                SettingsPage.SOURCE_DETAILS -> sourceId
                SettingsPage.LICENSE -> licenseId
                else -> ""
            }
            holder.SaveableStateProvider(key) {
                var renameOpen by rememberSaveable { mutableStateOf(false) }
                var removeOpen by rememberSaveable { mutableStateOf(false) }
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
                        SettingsPage.HOME -> home(state, actions, ::navigate)
                        SettingsPage.SOURCES -> sources(state, actions) { sourceId = it; navigate(SettingsPage.SOURCE_DETAILS) }
                        SettingsPage.SOURCE_DETAILS -> if (detailsSource == null) {
                            item { KgsSettingsFooter("This Source is no longer available.") }
                        } else sourceDetails(detailsSource, state.defaultSourceId == detailsSource.id, actions,
                            onRename = { renameOpen = true }, onRemove = { removeOpen = true })
                        SettingsPage.NOTES -> notesAndEditor(state, actions)
                        SettingsPage.APPEARANCE -> appearance(state, actions)
                        SettingsPage.PRIVACY -> privacy(state, actions)
                        SettingsPage.ABOUT -> about(actions,
                            onOpenLicense = { licenseId = NotesLicenseId; navigate(SettingsPage.LICENSE, true) },
                            onOpenLibraries = { navigate(SettingsPage.OPEN_SOURCE, true) })
                        SettingsPage.OPEN_SOURCE -> openSourceLicenses { licenseId = it; navigate(SettingsPage.LICENSE, true) }
                        SettingsPage.LICENSE -> license?.let { (library, paragraphs) -> licenseText(library, paragraphs) }
                    }
                }
                if (destination == SettingsPage.SOURCE_DETAILS && detailsSource != null) {
                    SourceDialogs(detailsSource, actions, renameOpen, { renameOpen = it }, removeOpen, { removeOpen = it },
                        onRemoved = { navigate(SettingsPage.SOURCES, false) })
                }
            }
        }
    }
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

private fun LazyListScope.home(state: NotesUiState, actions: NotesActions, navigate: (SettingsPage, Boolean) -> Unit) {
    group("notes", "Your Notes") {
        row("sources") { NavigationRow("Sources", state.sources.joinToString { it.name }, "settings-open-SOURCES", it,
            onClick = { navigate(SettingsPage.SOURCES, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { CloudGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("editor") { NavigationRow("Notes & editor", "${editorModeLabel(state.defaultEditorMode)} · ${state.noteFormatPreference.label}", "settings-open-NOTES", it,
            onClick = { navigate(SettingsPage.NOTES, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
    }
    group("personal", "Make it yours") {
        row("appearance") { NavigationRow("Appearance", listOfNotNull(darkModeSummary(state.darkMode), state.theme.label, layoutLabel(state.libraryView)).joinToString(" · "), "settings-open-APPEARANCE", it,
            onClick = { navigate(SettingsPage.APPEARANCE, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.tertiary) { AppearanceGlyph(MaterialTheme.colorScheme.tertiary) } } }
        row("privacy") { NavigationRow("Photos & privacy", metadataTitle(state.attachmentMetadataPreference), "settings-open-PRIVACY", it,
            onClick = { navigate(SettingsPage.PRIVACY, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { PrivacyGlyph(MaterialTheme.colorScheme.secondary) } } }
    }
    group("project", "Project") {
        row("about") { NavigationRow("About KGS Notes", "Version ${BuildConfig.VERSION_NAME}", "settings-open-ABOUT", it,
            onClick = { navigate(SettingsPage.ABOUT, true) }) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
        row("sponsor") { NavigationRow("Sponsor this project", "GitHub Sponsors", "settings-sponsor", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesSponsorUrl) }) { KgsSettingsIcon(SponsorRed) { Icon(Icons.Default.Favorite, null, tint = SponsorRed, modifier = Modifier.size(20.dp)) } } }
        row("bug") { NavigationRow("Report a bug", "Notes issues on GitHub", "settings-report-bug", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesIssuesUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.onSurfaceVariant) { BugGlyph(MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}

private fun LazyListScope.sources(state: NotesUiState, actions: NotesActions, onOpen: (String) -> Unit) {
    group("sources", "Your Sources") {
        state.sources.forEach { source ->
            row(source.id.value) {
                val summary = buildList {
                    add(sourceKind(source))
                    if (state.defaultSourceId == source.id) add("Default")
                    if (!source.visible) add("Hidden")
                }.joinToString(" · ")
                NavigationRow(source.name, summary, "source-settings-${source.id.value}", it, onClick = { onOpen(source.id.value) }) {
                    KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { SourceGlyph(source) }
                }
            }
        }
        row("add") {
            KgsSettingsItem("Add Source", it, summary = "Connect Nextcloud", onClick = actions.onAddSource, titleColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings-add-source").semantics { contentDescription = "Add Nextcloud Source" },
                leading = { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) } })
        }
    }
    // Choosing a default only means something once there is more than one place to save to.
    val choices = state.sources.filter { it.connected || it.id == LocalSourceId }
    if (choices.size > 1) group("default", "Save new Notes to") {
        choices.forEach { source ->
            row(source.id.value) {
                KgsSettingsRadioItem(source.name, selected = state.defaultSourceId == source.id, onClick = { actions.onDefaultSourceChange(source.id) },
                    position = it, summary = sourceKind(source), modifier = Modifier.testTag("default-source-${source.id.value}"))
            }
        }
    }
}

private fun LazyListScope.sourceDetails(source: SourceChoice, isDefault: Boolean, actions: NotesActions, onRename: () -> Unit, onRemove: () -> Unit) {
    val local = source.id == LocalSourceId
    group("status") {
        row("summary") {
            KgsSettingsItem(if (local) "Local Source" else "Nextcloud Notes Source", it,
                summary = when { local -> "Stored on this device"; source.connected -> "Connected"; else -> "Disconnected · reconnect to sync" },
                leading = { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { SourceGlyph(source) } })
        }
    }
    group("library", "Library") {
        row("visibility") {
            KgsSettingsSwitchItem("Show in library", checked = source.visible, onCheckedChange = { actions.onToggleSourceVisibility(source.id) },
                position = it, summary = if (source.visible) "Notes appear with your other Notes" else "Notes stay on this device, out of view",
                modifier = Modifier.testTag("source-visibility"))
        }
        if (source.connected) row("default") {
            if (isDefault) {
                KgsSettingsItem("Default Source", it, summary = "New Notes are saved here",
                    trailing = { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
            } else {
                KgsSettingsItem("Make Default Source", it, summary = "Save new Notes here", onClick = { actions.onDefaultSourceChange(source.id) },
                    modifier = Modifier.testTag("source-make-default"))
            }
        }
    }
    if (!local) group("connection", "Connection") {
        if (source.connected) {
            row("rename") { KgsSettingsItem("Rename Source", it, summary = source.name, onClick = onRename, modifier = Modifier.testTag("source-rename"),
                leading = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
            row("sync") { KgsSettingsItem("Sync now", it, summary = "Refresh Notes from the server", onClick = actions.onRefreshSources,
                modifier = Modifier.testTag("source-sync"), leading = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) }
        }
        row("connection") {
            KgsSettingsItem(if (source.connected) "Update connection" else "Reconnect Source", it, summary = "Nextcloud sign-in",
                onClick = { if (source.connected) actions.onEditSourceConnection(source.id) else actions.onAddSource() },
                modifier = Modifier.testTag("source-connection"), leading = { CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
    if (!local) group("remove", footer = "Notes on the server stay there.") {
        row("remove") {
            KgsSettingsItem("Remove Source", it, summary = "Remove from this device", onClick = onRemove, titleColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("source-remove"), leading = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
        }
    }
}

private fun LazyListScope.notesAndEditor(state: NotesUiState, actions: NotesActions) {
    group("editor", "Editor", footer = "Notes you switch from the editor toolbar keep their own mode.") {
        row("mode") {
            KgsSettingsSegmented(EditorMode.entries, state.defaultEditorMode, actions.onDefaultEditorModeChange, label = ::editorModeLabel,
                position = it, title = "Open Notes in", segmentModifier = { mode -> Modifier.testTag("editor-mode-${mode.name}") })
        }
    }
    group("format", "Nextcloud file format", footer = "Local Source always uses Markdown.") {
        NoteFormatPreference.entries.forEach { option ->
            row(option.name) {
                KgsSettingsRadioItem(option.label, selected = state.noteFormatPreference == option, onClick = { actions.onNoteFormatPreferenceChange(option) },
                    position = it, modifier = Modifier.testTag("format-choice-${option.name}"), summary = when (option) {
                        NoteFormatPreference.MARKDOWN -> "Keeps headings, lists and links"
                        NoteFormatPreference.TEXT -> "Plain text files"
                        NoteFormatPreference.FOLLOW_SOURCE -> "Use the server's default"
                    })
            }
        }
    }
}

private fun LazyListScope.appearance(state: NotesUiState, actions: NotesActions) {
    group("theme", "Theme") {
        row("mode") {
            KgsSettingsSegmented(DarkModePreference.entries, state.darkMode, actions.onDarkModeChange, label = { mode -> mode.label },
                position = it, segmentModifier = { mode -> Modifier.testTag("dark-mode-${mode.name}") })
        }
        row("tiles") {
            KgsSettingsTiles(NotesTheme.entries, state.theme, actions.onThemeChange, label = { theme -> theme.label }, position = it,
                tileModifier = { theme -> Modifier.testTag("theme-choice-${theme.name}") }) { theme -> ThemePreview(theme) }
        }
    }
    group("library", "Library") {
        row("layout") {
            KgsSettingsTiles(LibraryView.entries, state.libraryView, { view -> if (state.libraryView != view) actions.onToggleLibraryView() },
                label = ::layoutLabel, position = it, tileModifier = { view -> Modifier.testTag("layout-choice-${view.name}") }) { view -> LayoutPreview(view) }
        }
        row("grouping") {
            KgsSettingsSwitchItem("Group by date", checked = state.groupByDate, onCheckedChange = actions.onGroupByDateChange, position = it,
                summary = "Today, Yesterday and earlier", modifier = Modifier.testTag("group-by-date"))
        }
    }
}

private fun LazyListScope.privacy(state: NotesUiState, actions: NotesActions) {
    group("metadata", "When adding photos", footer = "Private metadata can include location and camera details.") {
        AttachmentMetadataPreference.entries.forEach { option ->
            row(option.name) {
                KgsSettingsRadioItem(metadataTitle(option), selected = state.attachmentMetadataPreference == option,
                    onClick = { actions.onAttachmentMetadataPreferenceChange(option) }, position = it, summary = metadataSummary(option),
                    modifier = Modifier.testTag("metadata-choice-${option.name}"))
            }
        }
    }
}

private fun LazyListScope.about(actions: NotesActions, onOpenLicense: () -> Unit, onOpenLibraries: () -> Unit) {
    group("app") { row("identity") { AboutIdentity(it) } }
    group("project", footer = "© 2026 KGS501. KGS Notes is free software: you can redistribute and modify it under the terms " +
        "of the GNU General Public License, version 3 or any later version. It comes with no warranty.") {
        row("source") { NavigationRow("Source code", "github.com/KGS501/kgs-notes", "about-source-code", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesSourceCodeUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { CodeGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("privacy") { NavigationRow("Privacy policy", "github.com/KGS501/kgs-notes", "about-privacy-policy", it, external = true,
            onClick = { actions.onOpenProjectLink(NotesPrivacyPolicyUrl) }) { KgsSettingsIcon(MaterialTheme.colorScheme.secondary) { PrivacyGlyph(MaterialTheme.colorScheme.secondary) } } }
        row("license") { NavigationRow("License", "GPL-3.0-or-later", "about-license", it,
            onClick = onOpenLicense) { KgsSettingsIcon(MaterialTheme.colorScheme.tertiary) { LicenseGlyph(MaterialTheme.colorScheme.tertiary) } } }
        row("libraries") { NavigationRow("Open-source licenses", "Libraries KGS Notes is built with", "about-open-source-licenses", it,
            onClick = onOpenLibraries) { KgsSettingsIcon(MaterialTheme.colorScheme.primary) { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } } }
    }
}

/**
 * Software shipped in the app and the bundled text of its license, so notices are readable offline.
 * Editor notices are generated by tools/generate_open_source_notices.py.
 */
private class OpenSourceLibrary(val name: String, val purpose: String, val license: String, val asset: String, val notice: String? = null)

private val NotesLicense = OpenSourceLibrary(NotesLicenseId, "Note-taking app", "GPL-3.0-or-later", "gpl-3.0.txt", "© 2026 KGS501")

private val AppLibraries = listOf(
    OpenSourceLibrary("Android Jetpack", "Compose, Material 3, Activity, Lifecycle, WebKit and more", "Apache-2.0", "apache-2.0.txt",
        "Copyright The Android Open Source Project"),
    OpenSourceLibrary("Kotlin", "Standard library, Coroutines, Serialization and annotations", "Apache-2.0", "apache-2.0.txt",
        "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
    OpenSourceLibrary("OkHttp & Okio", "Networking", "Apache-2.0", "apache-2.0.txt", "Copyright Square, Inc."),
    OpenSourceLibrary("Guava & JSpecify", "Support annotations and futures", "Apache-2.0", "apache-2.0.txt",
        "Copyright The Guava Authors\nCopyright The JSpecify Authors"),
)

private val EditorLibraries = listOf(
    OpenSourceLibrary("Tiptap", "Rich Mode editor", "MIT", "editor-tiptap.txt"),
    OpenSourceLibrary("ProseMirror", "Editing toolkit", "MIT", "editor-prosemirror.txt"),
    OpenSourceLibrary("Marked", "Markdown parsing", "MIT", "editor-marked.txt"),
    OpenSourceLibrary("Linkify", "Link detection", "MIT", "editor-linkify.txt"),
)

private fun licenseFor(id: String): OpenSourceLibrary =
    (AppLibraries + EditorLibraries).firstOrNull { it.name == id } ?: NotesLicense

private fun LazyListScope.openSourceLicenses(onOpen: (String) -> Unit) {
    listOf("app" to AppLibraries, "editor" to EditorLibraries).forEach { (key, libraries) ->
        group(key, if (key == "app") "App" else "Editor") {
            libraries.forEach { library ->
                row(library.name) {
                    KgsSettingsItem(library.name, it, summary = "${library.purpose} · ${library.license}",
                        onClick = { onOpen(library.name) }, modifier = Modifier.testTag("library-${library.name}"),
                        trailing = { ArrowGlyph(false, MaterialTheme.colorScheme.onSurfaceVariant) })
                }
            }
        }
    }
}

/** License text reads as a document: paragraphs on the page background rather than in cards. */
private fun LazyListScope.licenseText(library: OpenSourceLibrary, paragraphs: List<String>) {
    group("summary", footer = library.notice) {
        row("license") { KgsSettingsItem(library.license, it, summary = library.purpose) }
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
    KgsSettingsContainer(position) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The mark is drawn white; the brand accent tints it and follows the chosen theme.
            Image(painterResource(R.drawable.kgs_logo), contentDescription = null,
                colorFilter = ColorFilter.tint(LocalKgsAccentColors.current.focus),
                modifier = Modifier.size(64.dp).padding(bottom = 8.dp))
            Text("KGS Notes", style = MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("about-version"))
        }
    }
}

@Composable
private fun NavigationRow(title: String, summary: String, tag: String, position: KgsListPosition, onClick: () -> Unit,
    external: Boolean = false, icon: @Composable () -> Unit) {
    KgsSettingsItem(title, position, summary = summary, onClick = onClick, modifier = Modifier.testTag(tag),
        leading = icon, trailing = { ArrowGlyph(external, MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Composable
private fun SourceDialogs(source: SourceChoice, actions: NotesActions, renameOpen: Boolean, onRenameOpen: (Boolean) -> Unit,
    removeOpen: Boolean, onRemoveOpen: (Boolean) -> Unit, onRemoved: () -> Unit) {
    var removalRequested by remember { mutableStateOf(false) }
    var name by rememberSaveable(source.name) { mutableStateOf(source.name) }
    KgsSheetDialog(
        expanded = renameOpen,
        onDismissRequest = { onRenameOpen(false) },
        title = { Text("Rename Source") },
        text = { KgsTextField(name, { name = it }, modifier = Modifier.testTag("rename-source-field")) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                actions.onRenameSource(source.id, name)
                onRenameOpen(false)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onRenameOpen(false) }) { Text("Cancel") } },
    )
    KgsSheetDialog(
        expanded = removeOpen,
        onDismissRequest = { onRemoveOpen(false) },
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Remove ${source.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Removes this Source and its Working Copies from this device. Notes on the server stay there.")
                Text("Sync, move, or export pending edits first.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("source-remove-confirm"), onClick = {
                onRemoveOpen(false)
                removalRequested = true
            }) { Text("Remove Source", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { onRemoveOpen(false) }) { Text("Cancel") } },
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

private fun sourceKind(source: SourceChoice): String = when {
    source.id == LocalSourceId -> "On this device"
    !source.connected -> "Disconnected"
    else -> "Nextcloud"
}

private fun editorModeLabel(mode: EditorMode): String = when (mode) {
    EditorMode.RICH -> "Rich Mode"
    EditorMode.SOURCE -> "Source Mode"
}

/** Only an override is worth mentioning; following the system is the expected default. */
private fun darkModeSummary(mode: DarkModePreference): String? = mode.label.takeIf { mode != DarkModePreference.SYSTEM }

private fun layoutLabel(view: LibraryView): String = if (view == LibraryView.CARDS) "Cards" else "List"

private fun metadataTitle(option: AttachmentMetadataPreference): String = when (option) {
    AttachmentMetadataPreference.ASK_EVERY_TIME -> "Ask every time"
    AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> "Remove private metadata"
    AttachmentMetadataPreference.KEEP_ORIGINAL -> "Keep original"
}

private fun metadataSummary(option: AttachmentMetadataPreference): String = when (option) {
    AttachmentMetadataPreference.ASK_EVERY_TIME -> "Choose before adding each photo"
    AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> "Strip location & camera details"
    AttachmentMetadataPreference.KEEP_ORIGINAL -> "Keep photo data intact"
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
