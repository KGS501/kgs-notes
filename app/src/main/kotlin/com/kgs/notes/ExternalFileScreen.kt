package com.kgs.notes

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
            } else commitProblem = "The editor couldn't finish saving your text. Keep the preview open and try again."
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
                ExpressiveIconButton("Close file", close) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close file", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(file.name.ifBlank { "File preview" }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).kgsHorizontalTextFade())
                AnimatedContent(file.copying || file.closing || committing, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "File operation") { operating ->
                    if (operating) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (!file.loading && file.name.isNotBlank()) {
                        ExpressiveIconButton("Save to KGS Notes", { sourceChooserOpen = !sourceChooserOpen }) {
                            SaveToNotesGlyph()
                        }
                    } else Spacer(Modifier.size(40.dp))
                }
            }
            if (file.name.isNotBlank()) {
                if (!file.writable && !busy) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Read-only file", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onEditOriginal) { Text("Edit original file…") }
                    }
                }
                AnimatedContent(
                    targetState = when {
                        file.copying -> "Saving a copy to KGS Notes…"
                        file.saving -> "Saving to original file…"
                        !file.writable -> "Choose the original file to grant edit access."
                        file.dirty -> "Unsaved changes"
                        else -> "Editing original file"
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
                        OutlinedButton(onClick = { commitThen(action = onRetry) }) { Text("Retry saving") }
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else Spacer(Modifier.weight(1f))
        }
        SourceDialog(
            expanded = sourceChooserOpen && !busy,
            current = null,
            sources = sources.filter { it.connected && it.writable },
            explanation = "The original file stays in its current location.",
            onSelect = { source -> sourceChooserOpen = false; commitThen { onCopy(source) } },
            onDismiss = { sourceChooserOpen = false },
        )
        KgsConfirmationDialog(expanded = file.closeBlocked, onDismissRequest = onDismissCloseWarning, title = "Unsaved file changes") {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Keep your changes", style = MaterialTheme.typography.titleMedium)
                    Text("The original file couldn't be saved. Save a copy to a KGS Notes Source, or keep editing and retry.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onDismissCloseWarning(); sourceChooserOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save to KGS Notes")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismissCloseWarning, modifier = Modifier.weight(1f)) { Text("Keep editing") }
                        Button(onClick = onDiscard, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text("Discard edits")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveToNotesGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp).semantics { contentDescription = "Save to KGS Notes" }) {
        val unit = size.width / 24f
        drawRoundRect(color, Offset(3 * unit, 2 * unit), Size(14 * unit, 19 * unit), CornerRadius(2 * unit), style = Stroke(1.7f * unit))
        drawLine(color, Offset(7 * unit, 6 * unit), Offset(13 * unit, 6 * unit), 1.7f * unit, StrokeCap.Round)
        // The arrow belongs to the file, rather than resembling the New Note action.
        drawLine(color, Offset(13 * unit, 13 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(color, Offset(17 * unit, 10 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
        drawLine(color, Offset(17 * unit, 16 * unit), Offset(20 * unit, 13 * unit), 1.7f * unit, StrokeCap.Round)
    }
}
