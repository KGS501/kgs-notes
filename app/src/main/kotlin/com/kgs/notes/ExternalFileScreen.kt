package com.kgs.notes

import com.kgs.notes.design.R.string as UiR

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import com.kgs.notes.design.KgsConfirmationDialog
import com.kgs.notes.design.KgsSheetDock
import com.kgs.notes.design.LocalKgsSheetDock
import com.kgs.notes.design.kgsSheetControls
import com.kgs.notes.design.kgsHorizontalTextFade
import com.kgs.notes.editor.EditorMode
import androidx.compose.ui.platform.LocalContext
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.editor.KgsMarkdownEditor
import com.kgs.notes.engine.SourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** No engine Note identity exists here until the user explicitly chooses a Source. */
@Composable
internal fun ExternalFileScreen(
    file: ExternalFileUiState,
    sources: List<SourceChoice>,
    onTextChange: (String) -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onCopy: (SourceId) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDismissCloseWarning: () -> Unit = {},
    onEditOriginal: () -> Unit = {},
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current

    val fallbackDock = remember { KgsSheetDock() }
    val dock = LocalKgsSheetDock.current ?: fallbackDock
    val keyboard = LocalSoftwareKeyboardController.current
    var sourceChooserOpen by rememberSaveable(file.sessionKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentText by rememberUpdatedState(file.text)
    val snapshotReader = remember { arrayOf<suspend () -> String?>({ currentText }) }
    var committing by remember { mutableStateOf(false) }
    var freezingInput by remember { mutableStateOf(false) }
    var commitProblem by remember { mutableStateOf<String?>(null) }
    val busy = file.copying || file.closing || file.loading || committing
    fun commitThen(freezeInput: Boolean = true, action: () -> Unit) {
        if (busy || committing) return
        committing = true
        freezingInput = freezeInput
        commitProblem = null
        scope.launch {
            val text = withTimeoutOrNull(2_000) { snapshotReader[0]() }
            committing = false
            freezingInput = false
            if (text != null) {
                onTextChange(text)
                action()
            } else commitProblem = strings.getString(UiR.ui_the_editor_couldn_t_finish_saving_your_text_keep_the_preview_open_and_try_again)
        }
    }
    val close = {
        if (!file.copying && !file.closing && !committing) {
            keyboard?.hide()
            if (file.loading || file.name.isBlank()) onClose() else commitThen(action = onClose)
        }
    }
    BackHandler { close() }

    CompositionLocalProvider(LocalKgsSheetDock provides dock) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("external-file-preview")) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().kgsSheetControls(dock)
                    .padding(start = 6.dp, end = 6.dp, top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 2.dp),
            ) {
                ExpressiveIconButton(strings.getString(UiR.ui_close_file), close) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.getString(UiR.ui_close_file), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(file.name.ifBlank { strings.getString(UiR.ui_file_preview) }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).kgsHorizontalTextFade())
                AnimatedContent(file.copying || file.closing || committing, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "File operation") { operating ->
                    if (operating) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (!file.loading && file.name.isNotBlank()) {
                        ExpressiveIconButton(strings.getString(UiR.ui_save_to_kgs_notes), { sourceChooserOpen = !sourceChooserOpen }) {
                            SaveToNotesGlyph()
                        }
                    } else Spacer(Modifier.size(40.dp))
                }
            }
            if (file.name.isNotBlank()) {
                if (!file.writable && !busy) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.getString(UiR.ui_read_only_file), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onEditOriginal) { Text(strings.getString(UiR.ui_edit_original_file)) }
                    }
                }
                AnimatedContent(
                    targetState = when {
                        file.copying -> strings.getString(UiR.ui_saving_a_copy_to_kgs_notes)
                        file.saving -> strings.getString(UiR.ui_saving_to_original_file)
                        !file.writable -> strings.getString(UiR.ui_choose_the_original_file_to_grant_edit_access)
                        file.dirty -> strings.getString(UiR.ui_unsaved_changes)
                        else -> strings.getString(UiR.ui_editing_original_file)
                    },
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "External file status",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
                ) { status -> Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            AnimatedVisibility(file.error != null || commitProblem != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(commitProblem ?: file.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    if (file.dirty && file.writable && !busy) {
                        OutlinedButton(onClick = { commitThen(action = onRetry) }) { Text(strings.getString(UiR.ui_retry_saving)) }
                    }
                }
            }
            if (file.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (file.name.isNotBlank()) {
                KgsMarkdownEditor(
                    markdown = file.text,
                    requestedMode = file.mode,
                    onMarkdownChange = onTextChange,
                    onModeChange = { mode -> commitThen(freezeInput = false) { onModeChange(mode) } },
                    canAddImage = false,
                    allowRemoteResources = false,
                    readOnly = !file.writable || file.copying || file.closing || freezingInput,
                    onSnapshotReaderReady = { snapshotReader[0] = it },
                    // An opened file isn't part of the library, so only outside links can open.
                    onOpenLink = { href -> context.openLinkTarget(resolveNoteLink(href, LocalSourceId, "", emptyList())) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else Spacer(Modifier.weight(1f))
        }
        SourceDialog(
            expanded = sourceChooserOpen && !busy,
            current = null,
            sources = sources.filter { it.connected && it.writable },
            explanation = strings.getString(UiR.ui_the_original_file_stays_in_its_current_location),
            onSelect = { source -> sourceChooserOpen = false; commitThen { onCopy(source) } },
            onDismiss = { sourceChooserOpen = false },
        )
        KgsConfirmationDialog(expanded = file.closeBlocked, onDismissRequest = onDismissCloseWarning, title = strings.getString(UiR.ui_unsaved_file_changes)) {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(strings.getString(UiR.ui_keep_your_changes), style = MaterialTheme.typography.titleMedium)
                    Text(strings.getString(UiR.ui_the_original_file_couldn_t_be_saved_save_a_copy_to_a_kgs_notes_source_or_keep_editing),
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onDismissCloseWarning(); sourceChooserOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.getString(UiR.ui_save_to_kgs_notes))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismissCloseWarning, modifier = Modifier.weight(1f)) { Text(strings.getString(UiR.ui_keep_editing)) }
                        Button(onClick = onDiscard, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text(strings.getString(UiR.ui_discard_edits))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveToNotesGlyph() {
    val strings = androidx.compose.ui.platform.LocalResources.current

    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp).semantics { contentDescription = strings.getString(UiR.ui_save_to_kgs_notes) }) {
        val unit = size.width / 24f
        drawRoundRect(color, Offset(3 * unit, 2 * unit), Size(14 * unit, 19 * unit), CornerRadius(2 * unit), style = Stroke(1.7f * unit))
        drawLine(color, Offset(7 * unit, 6 * unit), Offset(13 * unit, 6 * unit), 1.7f * unit, StrokeCap.Round)
        // The arrow belongs to the file, rather than resembling the New Note action.
        drawLine(color, Offset(13 * unit, 13 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(color, Offset(17 * unit, 10 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(color, Offset(17 * unit, 16 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
    }
}
