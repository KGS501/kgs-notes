package com.kgs.notes

import android.content.Intent
import android.net.Uri
import com.kgs.notes.design.KgsSheetDock
import com.kgs.notes.design.KgsSheetExpansion
import com.kgs.notes.design.KgsExpandableSheetPanel
import com.kgs.notes.design.KgsConfirmationDialog
import com.kgs.notes.design.kgsSheetControls
import com.kgs.notes.design.LocalKgsSheetDock
import com.kgs.notes.design.rememberKgsDismissSheets
import com.kgs.notes.design.kgsNewNoteShadow
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.graphics.CompositingStrategy
import com.kgs.notes.design.LocalKgsInlineSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.design.NotesTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.LocalKgsDarkTheme
import com.kgs.notes.design.LocalKgsAccentColors
import com.kgs.notes.design.KgsCornerRadii
import com.kgs.notes.design.KgsDropdownMenuItem
import com.kgs.notes.design.KgsSearchField
import com.kgs.notes.design.KgsNoteTitleField
import com.kgs.notes.design.kgsHorizontalTextFade
import com.kgs.notes.design.KgsTextField
import com.kgs.notes.design.KgsExpressiveSurface
import com.kgs.notes.design.KgsBottomSheet
import com.kgs.notes.design.KgsSheetDialog
import com.kgs.notes.design.LocalKgsSheetContentHeight
import com.kgs.notes.design.KgsTooltip
import com.kgs.notes.design.defaultEffectsSpec
import com.kgs.notes.design.defaultSpatialSpec
import com.kgs.notes.design.fastEffectsSpec
import com.kgs.notes.design.fastSpatialSpec
import com.kgs.notes.design.rememberKgsHapticClick
import com.kgs.notes.design.collectIsVisuallyPressedAsState
import com.kgs.notes.editor.EditorMode
import androidx.compose.foundation.isSystemInDarkTheme
import com.kgs.notes.editor.KgsMarkdownEditor
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.AttachmentId
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.SourceId
import java.io.InputStream
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

data class NotesActions(
    val onCreate: () -> Unit = {},
    val onSelect: (NoteId) -> Unit = {},
    val onContentChange: (String) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onCategoryChange: (String) -> Unit = {},
    val onSourceChange: (SourceId) -> Unit = {},
    val onAddSource: () -> Unit = {},
    val onDefaultSourceChange: (SourceId) -> Unit = {},
    val onThemeChange: (NotesTheme) -> Unit = {},
    val onDarkModeChange: (DarkModePreference) -> Unit = {},
    val onDefaultEditorModeChange: (EditorMode) -> Unit = {},
    val onNoteFormatPreferenceChange: (NoteFormatPreference) -> Unit = {},
    val onBrowserSourceConnect: (String, String) -> Unit = { _, _ -> },
    val onAppPasswordSourceConnect: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val onDismissSourceConnection: () -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onMoveToTrash: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onDeletePermanently: () -> Unit = {},
    val onFilterChange: (LibraryFilter) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSortChange: (LibrarySort) -> Unit = {},
    val onToggleSortDirection: () -> Unit = {},
    val onToggleLibraryView: () -> Unit = {},
    val onGroupByDateChange: (Boolean) -> Unit = {},
    val onToggleSourceVisibility: (SourceId) -> Unit = {},
    val onDestinationChange: (LibraryDestination) -> Unit = {},
    val onLibraryCategoryChange: (String?) -> Unit = {},
    val onEditorModeChange: (EditorMode) -> Unit = {},
    val onAttachmentMetadataPreferenceChange: (AttachmentMetadataPreference) -> Unit = {},
    val onAddImage: () -> Unit = {},
    val openManagedAttachment: (String) -> InputStream? = { null },
    val onMessageDismissed: () -> Unit = {},
    val onRenameNote: (NoteId, String) -> Unit = { _, _ -> },
    val onSelectedNotesAction: (Set<NoteId>, NoteSelectionOperation, String) -> Unit = { _, _, _ -> },
    val onRenameSource: (SourceId, String) -> Unit = { _, _ -> },
    val onRemoveSource: (SourceId) -> Unit = {},
    val onRefreshSources: () -> Unit = {},
    val onEditSourceConnection: (SourceId) -> Unit = {},
    val onOpenProjectLink: (String) -> Unit = {},
)

@Composable
internal fun DarkModePreference.isDark(): Boolean = when (this) {
    DarkModePreference.SYSTEM -> isSystemInDarkTheme()
    DarkModePreference.LIGHT -> false
    DarkModePreference.DARK -> true
}

@Composable
fun KgsNotesApp(viewModel: NotesViewModel, onExternalPreviewClosed: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    KgsNotesTheme(darkTheme = state.darkMode.isDark(), theme = state.theme) {
        val editorDock = remember { KgsSheetDock() }
        CompositionLocalProvider(LocalKgsSheetDock provides if (state.externalFile != null || state.selectedNote?.state == NoteState.ACTIVE) editorDock else null) {
        val context = LocalContext.current
        var imageImportDialogOpen by rememberSaveable { mutableStateOf(false) }
        var removePrivateMetadata by rememberSaveable { mutableStateOf(true) }
        var editPermissionSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
        val externalEditPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val sessionKey = editPermissionSessionKey
            if (uri != null && sessionKey != null) viewModel.grantExternalEditing(sessionKey, uri)
            editPermissionSessionKey = null
        }
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.importImage(it, removePrivateMetadata) }
        }
        val externalFile = state.externalFile
        if (externalFile != null) {
            key(externalFile.sessionKey) {
            ExternalFileScreen(
                file = externalFile,
                sources = state.sources,
                onTextChange = { viewModel.updateExternalText(externalFile.sessionKey, it) },
                onModeChange = { viewModel.setExternalEditorMode(externalFile.sessionKey, it) },
                onCopy = { viewModel.copyExternalFileToSource(externalFile.sessionKey, it) },
                onClose = { viewModel.closeExternalFile(externalFile.sessionKey, onClosed = onExternalPreviewClosed) },
                onRetry = { viewModel.retryExternalSave(externalFile.sessionKey) },
                onDiscard = { viewModel.closeExternalFile(externalFile.sessionKey, discard = true, onClosed = onExternalPreviewClosed) },
                onDismissCloseWarning = { viewModel.dismissExternalCloseWarning(externalFile.sessionKey) },
                onEditOriginal = {
                    if (editPermissionSessionKey == null) {
                        editPermissionSessionKey = externalFile.sessionKey
                        runCatching {
                            externalEditPicker.launch(arrayOf("text/*", "application/octet-stream", "application/markdown", "application/x-markdown"))
                        }.onFailure {
                            editPermissionSessionKey = null
                            android.widget.Toast.makeText(context, "No file picker is available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            }
        } else KgsNotesScreen(
            state = state,
            actions = NotesActions(
                onCreate = viewModel::createNote,
                onSelect = viewModel::selectNote,
                onContentChange = viewModel::updateContent,
                onRename = viewModel::rename,
                onToggleFavorite = viewModel::toggleFavorite,
                onCategoryChange = viewModel::setCategory,
                onSourceChange = viewModel::changeSource,
                onAddSource = viewModel::showSourceConnection,
                onDefaultSourceChange = viewModel::setDefaultSource,
                onNoteFormatPreferenceChange = viewModel::setNoteFormatPreference,
                onThemeChange = viewModel::setTheme,
                onDarkModeChange = viewModel::setDarkMode,
                onDefaultEditorModeChange = viewModel::setDefaultEditorMode,
                onBrowserSourceConnect = viewModel::connectWithBrowser,
                onAppPasswordSourceConnect = viewModel::connectWithAppPassword,
                onDismissSourceConnection = viewModel::dismissSourceConnection,
                onCloseEditor = viewModel::closeEditor,
                onMoveToTrash = viewModel::moveToTrash,
                onRestore = viewModel::restore,
                onDeletePermanently = viewModel::deletePermanently,
                onFilterChange = viewModel::setFilter,
                onQueryChange = viewModel::setQuery,
                onSortChange = viewModel::setSort,
                onToggleSortDirection = viewModel::toggleSortDirection,
                onToggleLibraryView = viewModel::toggleLibraryView,
                onGroupByDateChange = viewModel::setGroupByDate,
                onToggleSourceVisibility = viewModel::toggleSourceVisibility,
                onDestinationChange = viewModel::openDestination,
                onLibraryCategoryChange = viewModel::openCategory,
                onEditorModeChange = viewModel::setEditorMode,
                onAttachmentMetadataPreferenceChange = viewModel::setAttachmentMetadataPreference,
                onAddImage = {
                    when (state.attachmentMetadataPreference) {
                        AttachmentMetadataPreference.ASK_EVERY_TIME -> imageImportDialogOpen = true
                        AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> {
                            removePrivateMetadata = true
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                        AttachmentMetadataPreference.KEEP_ORIGINAL -> {
                            removePrivateMetadata = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    }
                },
                openManagedAttachment = viewModel::openManagedAttachment,
                onMessageDismissed = viewModel::clearUserMessage,
                onRenameNote = viewModel::renameNote,
                onSelectedNotesAction = viewModel::applyToNotes,
                onRenameSource = viewModel::renameSource,
                onRemoveSource = viewModel::removeSource,
                onRefreshSources = viewModel::refreshSources,
                onEditSourceConnection = viewModel::editSourceConnection,
                onOpenProjectLink = { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { android.widget.Toast.makeText(context, "No browser is available", android.widget.Toast.LENGTH_SHORT).show() }
                },
            ),
        )
        LaunchedEffect(state.sourceConnection.browserUrl) {
            val browserUrl = state.sourceConnection.browserUrl ?: return@LaunchedEffect
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
            }
            viewModel.browserLaunchHandled()
        }
        ImageImportDialog(
            expanded = imageImportDialogOpen,
            removePrivateMetadata = removePrivateMetadata,
            onRemovePrivateMetadataChange = { removePrivateMetadata = it },
            onChoose = {
                imageImportDialogOpen = false
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDismiss = { imageImportDialogOpen = false },
        )

        }
    }
}

private enum class NextcloudSetupMode { BROWSER, APP_PASSWORD }

@Composable
private fun NextcloudConnectionDialog(
    state: SourceConnectionUiState,
    onBrowserConnect: (String, String) -> Unit,
    onAppPasswordConnect: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    KgsBottomSheet(expanded = state.visible, onDismissRequest = onDismiss, title = "Nextcloud Source") {
        var sourceName by rememberSaveable(state.editingSourceId?.value) { mutableStateOf(state.initialName) }
        var serverUrl by rememberSaveable(state.editingSourceId?.value) { mutableStateOf(state.initialServerUrl) }
        var loginName by rememberSaveable(state.editingSourceId?.value) { mutableStateOf(state.initialLoginName) }
        var appPassword by rememberSaveable { mutableStateOf("") }
        var mode by rememberSaveable(state.editingSourceId?.value) {
            mutableStateOf(
                if (state.editingSourceId == null) NextcloudSetupMode.BROWSER else NextcloudSetupMode.APP_PASSWORD,
            )
        }
        val busy = state.phase in setOf(
            SourceConnectionPhase.STARTING,
            SourceConnectionPhase.WAITING_FOR_BROWSER,
            SourceConnectionPhase.VERIFYING,
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nextcloud-connection-dialog"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    if (state.editingSourceId == null) "Add Nextcloud Source" else "Update Nextcloud Source",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Notes stay available as offline Working Copies and sync through the Notes API.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KgsConnectionField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    label = "Source name",
                    enabled = !busy,
                )
                KgsConnectionField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = "Nextcloud address",
                    placeholder = "cloud.example.com",
                    enabled = !busy && state.editingSourceId == null,
                )
                if (mode == NextcloudSetupMode.APP_PASSWORD) {
                    KgsConnectionField(
                        value = loginName,
                        onValueChange = { loginName = it },
                        label = "Username",
                        enabled = !busy && state.editingSourceId == null,
                    )
                    KgsConnectionField(
                        value = appPassword,
                        onValueChange = { appPassword = it },
                        label = "App password",
                        enabled = !busy,
                        password = true,
                    )
                }
                if (state.message != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else if (state.phase == SourceConnectionPhase.CONNECTED) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF238B57),
                            )
                        }
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.phase == SourceConnectionPhase.ERROR) {
                                DestructiveRed
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (state.phase == SourceConnectionPhase.CONNECTED) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Done") }
                } else {
                    Button(
                        enabled = !busy && serverUrl.isNotBlank() &&
                            (mode == NextcloudSetupMode.BROWSER ||
                                (loginName.isNotBlank() && appPassword.isNotBlank())),
                        onClick = {
                            if (mode == NextcloudSetupMode.BROWSER) {
                                onBrowserConnect(sourceName, serverUrl)
                            } else {
                                onAppPasswordConnect(sourceName, serverUrl, loginName, appPassword)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (mode == NextcloudSetupMode.BROWSER) "Continue in browser" else "Connect")
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            mode = if (mode == NextcloudSetupMode.BROWSER) {
                                NextcloudSetupMode.APP_PASSWORD
                            } else {
                                NextcloudSetupMode.BROWSER
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            if (mode == NextcloudSetupMode.BROWSER) {
                                "Use an app password instead"
                            } else {
                                "Use browser authorization"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KgsConnectionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    placeholder: String = "",
    password: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KgsTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(
                    when (label) {
                        "Source name" -> "nextcloud-source-name"
                        "Nextcloud address" -> "nextcloud-address"
                        "Username" -> "nextcloud-username"
                        else -> "nextcloud-app-password"
                    },
                ),
        )
    }
}

@Composable
private fun ImageImportDialog(
    expanded: Boolean,
    removePrivateMetadata: Boolean,
    onRemovePrivateMetadataChange: (Boolean) -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    KgsBottomSheet(expanded = expanded, onDismissRequest = onDismiss, title = "Add image") {

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("image-import-dialog"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text("Add an image", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose whether KGS Notes should remove location, device, and other private metadata.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                ModalChoiceRow(
                    label = "Remove private metadata",
                    selected = removePrivateMetadata,
                    onClick = { onRemovePrivateMetadataChange(true) },
                    icon = { PrivacyGlyph(MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                ModalChoiceRow(
                    label = "Keep original",
                    selected = !removePrivateMetadata,
                    onClick = { onRemovePrivateMetadataChange(false) },
                    icon = { ImageGlyph(MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                Spacer(Modifier.height(3.dp))
                Button(
                    onClick = rememberKgsHapticClick(onChoose),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose image")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun KgsNotesScreen(
    state: NotesUiState,
    actions: NotesActions,
    modifier: Modifier = Modifier,
) {
    val motion = LocalKgsMotion.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cardScrollOffset = rememberSaveable(state.destination, state.libraryCategory) { mutableFloatStateOf(0f) }
    var selectedIds by remember { mutableStateOf(setOf<NoteId>()) }
    var selectedTitles by remember { mutableStateOf(mapOf<NoteId, String>()) }
    var returnMorphInterrupted by remember { mutableStateOf(false) }
    val onLibraryScroll: () -> Unit = {
        if (state.selectedNote == null) returnMorphInterrupted = true
    }
    val toggleSelection: (NoteId) -> Unit = { id ->
        keyboard?.hide()
        focusManager.clearFocus()
        if (id in selectedIds) {
            selectedIds = selectedIds - id
            selectedTitles = selectedTitles - id
        } else {
            selectedIds = selectedIds + id
            selectedTitles = selectedTitles + (id to (state.notes.firstOrNull { it.id == id }?.title.orEmpty()))
        }
    }
    val closeEditor = {
        returnMorphInterrupted = false
        keyboard?.hide()
        focusManager.clearFocus()
        actions.onCloseEditor()
    }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<Pair<LibraryDestination, String?>?>(null) }
    val dismissSheets = rememberKgsDismissSheets()
    val openDrawer = {
        dismissSheets()
        selectedIds = emptySet()
        selectedTitles = emptyMap()
        drawerOpen = true
    }
    var newNoteMorphActive by remember { mutableStateOf(false) }
    LaunchedEffect(state.userMessage) {
        if (state.userMessage != null) {
            delay(4_000)
            actions.onMessageDismissed()
        }
    }
    BackHandler(enabled = state.selectedNote != null, onBack = closeEditor)
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet(); selectedTitles = emptyMap() }
    BackHandler(enabled = drawerOpen || pendingNavigation != null) { pendingNavigation = null; drawerOpen = false }
    BackHandler(
        enabled = state.selectedNote == null && selectedIds.isEmpty() && !drawerOpen && pendingNavigation == null &&
            (state.destination != LibraryDestination.NOTES || state.libraryCategory != null),
    ) {
        if (state.destination == LibraryDestination.NOTES) actions.onLibraryCategoryChange(null)
        else actions.onDestinationChange(LibraryDestination.NOTES)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val twoPane = maxWidth >= 760.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                LibraryPane(
                    state = state,
                    actions = actions,
                    onOpenDrawer = openDrawer,
                    selectedId = state.selectedNote?.id,
                    cardScrollOffset = cardScrollOffset,
                    selectedIds = selectedIds,
                    onToggleSelection = toggleSelection,
                    selectionActive = selectedIds.isNotEmpty(),
                    modifier = Modifier
                        .widthIn(min = 330.dp, max = 410.dp)
                        .fillMaxHeight(),
                )
                AnimatedContent(
                    targetState = state.selectedNote,
                    contentKey = { it?.id },
                    transitionSpec = {
                        fadeIn(motion.defaultEffectsSpec()) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    label = "selected note",
                    modifier = Modifier.weight(1f),
                ) { note ->
                    if (note == null) {
                        NoSelection(Modifier.fillMaxSize())
                    } else {
                        EditorPane(state, note, actions, showBack = false, Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                val sharedScope = this
                val morphConfig = remember {
                    object : SharedTransitionScope.SharedContentConfig {
                        override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                            get() = !returnMorphInterrupted
                        // Scrolling takes over immediately, including mid-morph.
                        override val shouldKeepEnabledForOngoingAnimation: Boolean = false
                    }
                }
                AnimatedContent(
                    targetState = state.selectedNote,
                    contentKey = { it?.id },
                    transitionSpec = {
                        if (newNoteMorphActive) {
                            fadeIn(motion.defaultEffectsSpec()) togetherWith
                                fadeOut(motion.fastEffectsSpec())
                        } else {
                            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                        }
                    },
                    label = "library editor navigation",
                    modifier = Modifier.fillMaxSize(),
                ) { note ->
                    val visibilityScope = this
                    val noteMorph = NoteMorph(sharedScope, visibilityScope, morphConfig)
                    if (note == null) {
                        val libraryActions = actions.copy(
                            onCreate = {
                                returnMorphInterrupted = false
                                newNoteMorphActive = true
                                actions.onCreate()
                            },
                            onSelect = { id ->
                                returnMorphInterrupted = false
                                newNoteMorphActive = false
                                actions.onSelect(id)
                            },
                        )
                        LibraryPane(
                            state = state,
                            actions = libraryActions,
                            onOpenDrawer = openDrawer,
                            selectedId = null,
                            cardScrollOffset = cardScrollOffset,
                            noteMorph = noteMorph,
                            onScroll = onLibraryScroll,
                            selectedIds = selectedIds,
                            onToggleSelection = toggleSelection,
                            selectionActive = selectedIds.isNotEmpty(),
                            newNoteButtonModifier = Modifier.newNoteMorphBounds(
                                sharedScope = sharedScope,
                                visibilityScope = visibilityScope,
                                ownCorner = 18.dp,
                                counterpartCorner = 0.dp,
                                // Register and measure the FAB before any tap. Adding
                                // shared bounds in onCreate loses the first source frame.
                                enabled = true,
                                config = morphConfig,
                            ).then(if (newNoteMorphActive) Modifier else Modifier.newNoteButtonLayer(noteMorph, shadow = false)),
                            newNoteButtonShadowModifier = Modifier.newNoteMorphShadow(sharedScope, visibilityScope, button = true, config = morphConfig)
                                .then(if (newNoteMorphActive) Modifier else Modifier.newNoteButtonLayer(noteMorph, shadow = true)),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        if (newNoteMorphActive) {
                            Box(Modifier.fillMaxSize()
                                .newNoteMorphShadow(sharedScope, visibilityScope, button = false, config = morphConfig)
                                .testTag("editor-morph-shadow"))
                        }
                        EditorPane(
                            state = state,
                            note = note,
                            actions = actions.copy(onCloseEditor = closeEditor),
                            showBack = true,
                            noteMorph = if (newNoteMorphActive) null else noteMorph,
                            stableLayoutScope = sharedScope,

                            modifier = Modifier
                                .fillMaxSize()
                                .noteMorphBounds(
                                    if (newNoteMorphActive) null else noteMorph,
                                    note.id, "container", 0.dp, 14.dp,
                                )
                                .newNoteMorphBounds(
                                    sharedScope = sharedScope,
                                    visibilityScope = visibilityScope,
                                    ownCorner = 0.dp,
                                    counterpartCorner = 18.dp,
                                    enabled = newNoteMorphActive,
                                    config = morphConfig,
                                ),
                        )
                    }
                }
            }
        }

        var displayedSelectedIds by remember { mutableStateOf(emptySet<NoteId>()) }
        if (selectedIds.isNotEmpty()) displayedSelectedIds = selectedIds
        val selectionForBar = selectedIds.ifEmpty { displayedSelectedIds }
        val selectionExpansion = remember { KgsSheetExpansion() }
        KgsBottomSheet(
            expanded = selectedIds.isNotEmpty() && state.selectedNote == null,
            modal = false,
            title = "Selected Notes",
            animateSizeChanges = false,
            expansion = selectionExpansion,
            onHidden = { selectionExpansion.expanded = false; selectionExpansion.available = false },
            onDismissRequest = { selectedIds = emptySet(); selectedTitles = emptyMap() },
        ) {
            NoteSelectionBar(
                expansion = selectionExpansion,
                count = selectionForBar.size,
                interactive = selectedIds.isNotEmpty(),
                selectedTitles = selectionForBar.map { id ->
                    state.notes.firstOrNull { it.id == id }?.title ?: selectedTitles[id].orEmpty()
                },
                selectedTitle = state.notes.firstOrNull { it.id == selectionForBar.singleOrNull() }?.title.orEmpty(),
                currentCategory = state.notes.filter { it.id in selectionForBar }.map { it.category }.distinct().singleOrNull(),
                currentSource = state.notes.filter { it.id in selectionForBar }.map { it.sourceId }.distinct().singleOrNull(),
                allFavorite = state.notes.filter { it.id in selectionForBar }
                    .let { visible -> visible.isNotEmpty() && visible.all { it.favorite } },
                moveStatus = state.notes.filter { it.id in selectionForBar }
                    .map { noteSaveStatus(it.syncState) }.distinct().singleOrNull() ?: EditorSaveStatus.SAVED_LOCALLY,
                destination = state.destination,
                categories = state.categories,
                sources = state.sources,
                onDismiss = { selectedIds = emptySet(); selectedTitles = emptyMap() },
                onRename = { title ->
                    selectionForBar.singleOrNull()?.let { actions.onRenameNote(it, title) }
                },
                onAction = { operation, value ->
                    actions.onSelectedNotesAction(selectionForBar, operation, value)
                },
            )
        }

        NotesDrawer(
            visible = drawerOpen,
            state = state,
            onDismiss = { drawerOpen = false },
            onToggleSource = actions.onToggleSourceVisibility,
            onSync = actions.onRefreshSources,
            onDestination = { destination ->
                pendingNavigation = destination to null
                drawerOpen = false
            },
            onCategory = { category ->
                pendingNavigation = LibraryDestination.NOTES to category
                drawerOpen = false
            },
            onClosed = {
                pendingNavigation?.let { (destination, category) ->
                    pendingNavigation = null
                    if (destination == LibraryDestination.NOTES) actions.onLibraryCategoryChange(category)
                    else actions.onDestinationChange(destination)
                }
            },
        )
        AnimatedContent(
            targetState = state.userMessage,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) +
                    scaleIn(motion.defaultSpatialSpec(), initialScale = .88f)) togetherWith
                    (fadeOut(motion.fastEffectsSpec()) +
                        scaleOut(motion.fastSpatialSpec(), targetScale = .92f))
            },
            label = "user message",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 58.dp),
        ) { message ->
            if (message != null) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    NextcloudConnectionDialog(
        state = state.sourceConnection,
        onBrowserConnect = actions.onBrowserSourceConnect,
        onAppPasswordConnect = actions.onAppPasswordSourceConnect,
        onDismiss = actions.onDismissSourceConnection,
    )

}

@Composable
private fun LibraryPane(
    state: NotesUiState,
    actions: NotesActions,
    onOpenDrawer: () -> Unit = {},
    selectedId: NoteId?,
    cardScrollOffset: MutableFloatState,
    noteMorph: NoteMorph? = null,
    selectedIds: Set<NoteId>,
    onToggleSelection: (NoteId) -> Unit,
    selectionActive: Boolean,
    newNoteButtonModifier: Modifier = Modifier,
    newNoteButtonShadowModifier: Modifier = Modifier.kgsNewNoteShadow(),
    onScroll: () -> Unit = {},
    modifier: Modifier,
) {
    val motion = LocalKgsMotion.current
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val location = LibraryLocation(state.destination, state.libraryCategory.takeIf { state.destination == LibraryDestination.NOTES })
    val latestLocation by rememberUpdatedState(location)
    val snapshots = remember { mutableMapOf<LibraryLocation, LibraryDisplay>() }
    SideEffect { snapshots[location] = LibraryDisplay(state, cardScrollOffset) }
    val background by animateColorAsState(
        if (state.destination == LibraryDestination.TRASH) {
            if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFE9E6E3) else Color(0xFF242526)
        } else MaterialTheme.colorScheme.background,
        motion.defaultEffectsSpec(), label = "Library background",
    )
    Scaffold(
        modifier = modifier.testTag("library-pane"),
        containerColor = background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state.destination == LibraryDestination.NOTES && !selectionActive) {
                KgsTooltip("New Note") {
                    Box(Modifier.padding(bottom = navigationBottom).size(56.dp)) {
                        Box(newNoteButtonShadowModifier.size(56.dp).testTag("new-note-shadow"))
                        KgsExpressiveSurface(
                            onClick = actions.onCreate,
                            color = LocalKgsAccentColors.current.fabContainer,
                            contentColor = LocalKgsAccentColors.current.fabContent,
                            restingCorners = KgsCornerRadii(18.dp),
                            showIndication = false,
                            modifier = newNoteButtonModifier
                                .size(56.dp).testTag("new-note-button"),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New note",
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
        ) {
            AnimatedContent(
                targetState = location,
                transitionSpec = {
                    if (targetState.destination == LibraryDestination.NOTES && targetState.category == null) {
                        slideInHorizontally(motion.defaultSpatialSpec()) { -it / 5 } +
                            fadeIn(motion.defaultEffectsSpec()) togetherWith
                            slideOutHorizontally(motion.fastSpatialSpec()) { it / 5 } +
                            fadeOut(motion.fastEffectsSpec())
                    } else {
                        slideInHorizontally(motion.defaultSpatialSpec()) { it / 5 } +
                            fadeIn(motion.defaultEffectsSpec()) togetherWith
                            slideOutHorizontally(motion.fastSpatialSpec()) { -it / 5 } +
                            fadeOut(motion.fastEffectsSpec())
                    }
                },
                label = "library destination",
                modifier = Modifier.fillMaxSize(),
            ) { shownLocation ->
                val destination = shownLocation.destination
                val displayed = if (shownLocation == location) LibraryDisplay(state, cardScrollOffset)
                    else snapshots[shownLocation] ?: LibraryDisplay(state, cardScrollOffset)
                DisposableEffect(shownLocation) {
                    onDispose { if (shownLocation != latestLocation) snapshots.remove(shownLocation) }
                }
                if (destination == LibraryDestination.SETTINGS) {
                    NotesSettingsScreen(
                        state = displayed.state,
                        actions = actions,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (destination == LibraryDestination.TRASH) {
                            LibraryHeader(
                                destination = destination,
                                onOpenDrawer = onOpenDrawer,
                                onBackToNotes = { actions.onDestinationChange(LibraryDestination.NOTES) },
                            )
                        }
                        LibraryCollection(
                            state = displayed.state,
                            actions = actions,
                            destination = destination,
                            selectedId = selectedId,
                            cardScrollOffset = displayed.scrollOffset,
                            noteMorph = noteMorph.takeIf { shownLocation == location },
                            onScroll = onScroll,
                            selectedIds = selectedIds,
                            onToggleSelection = onToggleSelection,
                            navigationBottom = navigationBottom,
                            onOpenDrawer = onOpenDrawer,
                        )
                    }
                }
            }
        }
    }
}

private data class LibraryDisplay(val state: NotesUiState, val scrollOffset: MutableFloatState)
private data class LibraryLocation(val destination: LibraryDestination, val category: String?)

@OptIn(ExperimentalSharedTransitionApi::class)
private data class NoteMorph(
    val scope: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val config: SharedTransitionScope.SharedContentConfig,
)

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.noteMorphBounds(
    morph: NoteMorph?,
    id: NoteId,
    element: String,
    ownCorner: androidx.compose.ui.unit.Dp = 0.dp,
    otherCorner: androidx.compose.ui.unit.Dp = 0.dp,
): Modifier {
    if (morph == null) return this
    val motion = LocalKgsMotion.current
    val corner by morph.visibility.transition.animateDp(
        transitionSpec = { motion.defaultSpatialSpec() },
        label = "Note morph corner",
    ) { if (it == EnterExitState.Visible) ownCorner else otherCorner }
    return with(morph.scope) {
        this@noteMorphBounds.sharedBounds(
            sharedContentState = rememberSharedContentState("note-${id.value}-$element", morph.config),
            animatedVisibilityScope = morph.visibility,
            boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec() },
            // The shape changes size; text layers only move and crossfade.
            // Never inherit an anisotropic transform from the card container.
            resizeMode = if (element == "container") SharedTransitionScope.ResizeMode.RemeasureToBounds
                else SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.None, Alignment.TopStart),
            placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner.coerceAtLeast(0.dp))),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            // Preview text must stay below the opaque footer backings in its
            // container layer throughout the morph, just as it does at rest.
            renderInOverlayDuringTransition = element != "body",
            zIndexInOverlay = if (element == "container") 1f else 3f,
        )
    }
}

/** Keep editor/preview content at its final geometry inside the resizing shape. */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.noteMorphStableLayout(morph: NoteMorph?): Modifier =
    if (morph == null) this else with(morph.scope) { this@noteMorphStableLayout.skipToLookaheadSize() }

@Composable
private fun noteMorphTitleStyle(morph: NoteMorph?, editor: Boolean): TextStyle {
    val own = if (editor) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    if (morph == null) return own
    val motion = LocalKgsMotion.current
    val progress by morph.visibility.transition.animateFloat(
        transitionSpec = { motion.defaultSpatialSpec() }, label = "Note title typography",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val other = if (editor) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
    return androidx.compose.ui.text.lerp(other, own, progress.coerceIn(0f, 1f))
}

/** Fade card-only details and their opaque backings as one layer. */
@Composable
private fun Modifier.noteMorphFooter(morph: NoteMorph?): Modifier {
    if (morph == null) return this
    val motion = LocalKgsMotion.current
    val opacity by morph.visibility.transition.animateFloat(
        transitionSpec = { motion.fastEffectsSpec() }, label = "Note footer visibility",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return graphicsLayer { alpha = opacity.coerceIn(0f, 1f) }
}

@Composable
private fun noteMorphCorner(morph: NoteMorph?, own: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    if (morph == null) return own
    val motion = LocalKgsMotion.current
    val corner by morph.visibility.transition.animateDp(
        transitionSpec = { motion.defaultSpatialSpec() }, label = "Note surface corner",
    ) { if (it == EnterExitState.Visible) own else 0.dp }
    return corner.coerceAtLeast(0.dp)
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.newNoteMorphBounds(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    ownCorner: androidx.compose.ui.unit.Dp,
    counterpartCorner: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    config: SharedTransitionScope.SharedContentConfig,
): Modifier {
    if (!enabled) return this
    val motion = LocalKgsMotion.current
    val animatedCorner = visibilityScope.transition.animateDp(
        transitionSpec = { motion.defaultSpatialSpec() },
        label = "new note container corner",
    ) { visibility ->
        if (visibility == EnterExitState.Visible) ownCorner else counterpartCorner
    }
    // Read the spring in drawing/layer updates, never in composition. The
    // editor and the library should not recompose just to round this outline.
    val overlayClip = remember(animatedCorner) {
        object : SharedTransitionScope.OverlayClip {
            private val path = androidx.compose.ui.graphics.Path()
            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: Rect,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ): androidx.compose.ui.graphics.Path {
                val radius = with(density) { animatedCorner.value.coerceAtLeast(0.dp).toPx() }
                    .coerceAtMost(minOf(bounds.width, bounds.height).coerceAtLeast(0f) / 2f)
                path.reset()
                path.addRoundRect(androidx.compose.ui.geometry.RoundRect(bounds, androidx.compose.ui.geometry.CornerRadius(radius)))
                return path
            }
        }
    }
    return with(sharedScope) {
        this@newNoteMorphBounds.sharedBounds(
            sharedContentState = rememberSharedContentState("new-note-container", config),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec() },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize,
            // Spatial springs intentionally overshoot. Corner radii cannot, so
            // constrain only the physical boundary while preserving the bounce.
            clipInOverlayDuringTransition = overlayClip,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            zIndexInOverlay = 2f,
        ).graphicsLayer {
            shape = RoundedCornerShape(animatedCorner.value.coerceAtLeast(0.dp))
            clip = true
        }
    }
}

/** A hardware shadow follows the container on its own unclipped layer. */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.newNoteMorphShadow(
    scope: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    button: Boolean,
    config: SharedTransitionScope.SharedContentConfig,
): Modifier {
    val motion = LocalKgsMotion.current
    val corner = visibility.transition.animateDp(
        transitionSpec = { motion.defaultSpatialSpec() }, label = "New Note shadow corner",
    ) { if ((it == EnterExitState.Visible) == button) 18.dp else 0.dp }
    val opacity = visibility.transition.animateFloat(
        transitionSpec = { tween(240, easing = motion.standard) }, label = "New Note shadow visibility",
    ) { if (it == EnterExitState.Visible && button) 1f else 0f }
    val overlayClip = remember {
        object : SharedTransitionScope.OverlayClip {
            private val path = androidx.compose.ui.graphics.Path()
            override fun getClipPath(
                sharedContentState: SharedTransitionScope.SharedContentState,
                bounds: Rect,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density,
            ): androidx.compose.ui.graphics.Path {
                path.reset()
                path.addRect(bounds.inflate(with(density) { 32.dp.toPx() }))
                return path
            }
        }
    }
    return with(scope) {
        this@newNoteMorphShadow.sharedBounds(
            sharedContentState = rememberSharedContentState("new-note-shadow", config),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec() },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            zIndexInOverlay = 1.5f,
            clipInOverlayDuringTransition = overlayClip,
        ).graphicsLayer {
            // dropShadow rasterizes and blurs a new mask when bounds change.
            // A RenderNode outline follows the morph without a screen-size bitmap.
            alpha = opacity.value.coerceIn(0f, 1f)
            shadowElevation = if (alpha > 0f) 9.dp.toPx() else 0f
            shape = RoundedCornerShape(corner.value.coerceAtLeast(0.dp))
            clip = false
            // Keep the soft outline outside the layer, including while fading.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }
}

/** The FAB stays above existing Note morphs; its shadow never inherits their bounds. */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.newNoteButtonLayer(morph: NoteMorph?, shadow: Boolean): Modifier {
    if (morph == null) return this
    val motion = LocalKgsMotion.current
    val opacity by morph.visibility.transition.animateFloat(
        transitionSpec = { tween(240, easing = motion.standard) }, label = "New Note button visibility",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return with(morph.scope) {
        this@newNoteButtonLayer.renderInSharedTransitionScopeOverlay(
            zIndexInOverlay = if (shadow) 3.5f else 4f,
        ).graphicsLayer {
            alpha = opacity.coerceIn(0f, 1f)
            // An offscreen alpha layer would cut the soft shadow to the FAB bounds.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCollection(
    state: NotesUiState,
    actions: NotesActions,
    destination: LibraryDestination,
    selectedId: NoteId?,
    cardScrollOffset: MutableFloatState,
    noteMorph: NoteMorph? = null,
    selectedIds: Set<NoteId>,
    onToggleSelection: (NoteId) -> Unit,
    navigationBottom: androidx.compose.ui.unit.Dp,
    onOpenDrawer: () -> Unit,
    onScroll: () -> Unit = {},
) {
    val motion = LocalKgsMotion.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
    var collectionOrigin by remember { mutableStateOf(Offset.Zero) }
    var searchImeWasVisible by remember { mutableStateOf(false) }
    val searchInteractions = remember { MutableInteractionSource() }
    val searchFocused by searchInteractions.collectIsFocusedAsState()
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = searchFocused) { focusManager.clearFocus() }
    LaunchedEffect(searchFocused, imeVisible) {
        if (!searchFocused) {
            searchImeWasVisible = false
        } else if (imeVisible) {
            searchImeWasVisible = true
        } else if (searchImeWasVisible) {
            // The IME consumes the first system Back event. Treat its dismissal as
            // leaving search so Back never needs a second press to release focus.
            focusManager.clearFocus()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { collectionOrigin = it.boundsInRoot().topLeft }
            .pointerInput(searchFocused, searchBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                    val bounds = searchBounds
                    if (up != null && searchFocused && bounds != null && !bounds.contains(down.position + collectionOrigin)) {
                        focusManager.clearFocus()
                    }
                }
            },
    ) {
        if (destination != LibraryDestination.NOTES) Spacer(Modifier.height(10.dp))
        if (destination == LibraryDestination.NOTES) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 5.dp),
            ) {
                ExpressiveIconButton(label = "Open Menu", onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onOpenDrawer()
                }) { Icon(Icons.Default.Menu, contentDescription = "Open menu") }
                KgsSearchField(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    placeholder = "Search notes",
                    interactionSource = searchInteractions,
                    modifier = Modifier.weight(1f)
                        .onGloballyPositioned { searchBounds = it.boundsInRoot() }
                        .testTag("notes-search"),
                )
            }
            state.libraryCategory?.let { category ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    LibraryCategoryHeader(category, state.notes.size,
                        ((maxWidth - 143.dp) / 4).coerceIn(28.dp, 44.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val filterBaseWidth = ((maxWidth - 143.dp) / 4).coerceIn(28.dp, 44.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val filterInteractions = remember {
                    LibraryFilter.entries.map { MutableInteractionSource() }
                }
                val filterPressed = filterInteractions.map { it.collectIsVisuallyPressedAsState().value }
                val pressedFilter = filterPressed.indexOfFirst { it }
                val neighbors = LibraryFilter.entries.indices.filter { index ->
                    index != pressedFilter && kotlin.math.abs(index - pressedFilter) <= 3
                }
                val totalWeight = neighbors.sumOf { index ->
                    (4 - kotlin.math.abs(index - pressedFilter)).toDouble()
                }
                LibraryFilter.entries.forEachIndexed { index, filter ->
                    val targetWidth = when {
                        pressedFilter < 0 -> filterBaseWidth
                        index == pressedFilter -> filterBaseWidth * 1.15f
                        index in neighbors -> filterBaseWidth - filterBaseWidth * .15f *
                            ((4 - kotlin.math.abs(index - pressedFilter)) / totalWeight).toFloat()
                        else -> filterBaseWidth
                    }
                    val filterWidth by animateDpAsState(
                        targetValue = targetWidth,
                        animationSpec = motion.fastSpatialSpec(),
                        label = "filter group width",
                    )
                    LibraryFilterButton(
                        filter = filter,
                        selected = state.filter == filter,
                        pendingSyncCount = state.pendingSyncCount,
                        interactionSource = filterInteractions[index],
                        modifier = Modifier.width(filterWidth),
                        onClick = { actions.onFilterChange(filter) },
                    )
                    if (index != LibraryFilter.entries.lastIndex) Spacer(Modifier.width(3.dp))
                }
                Spacer(Modifier.weight(1f))
                val directionInteractions = remember { MutableInteractionSource() }
                val sortInteractions = remember { MutableInteractionSource() }
                val viewInteractions = remember { MutableInteractionSource() }
                val viewPressed by viewInteractions.collectIsVisuallyPressedAsState()
                val directionPressed by directionInteractions.collectIsVisuallyPressedAsState()
                val sortPressed by sortInteractions.collectIsVisuallyPressedAsState()
                val directionWidth by animateDpAsState(
                    targetValue = when {
                        directionPressed -> 50.6.dp
                        sortPressed -> 40.7.dp
                        viewPressed -> 42.5.dp
                        else -> 44.dp
                    },
                    animationSpec = motion.fastSpatialSpec(),
                    label = "sort direction group width",
                )
                val sortWidth by animateDpAsState(
                    targetValue = when {
                        sortPressed -> 50.6.dp
                        directionPressed -> 39.6.dp
                        viewPressed -> 39.5.dp
                        else -> 44.dp
                    },
                    animationSpec = motion.fastSpatialSpec(),
                    label = "sort group width",
                )
                val viewWidth by animateDpAsState(
                    targetValue = when {
                        viewPressed -> 46.dp
                        sortPressed -> 36.7.dp
                        directionPressed -> 37.8.dp
                        else -> 40.dp
                    },
                    animationSpec = motion.fastSpatialSpec(),
                    label = "view group width",
                )
                KgsTooltip(if (state.ascending) "Ascending" else "Descending") {
                    KgsExpressiveSurface(
                        onClick = actions.onToggleSortDirection,
                        color = MaterialTheme.colorScheme.surface,
                        restingCorners = KgsCornerRadii(
                            topStart = 12.dp,
                            topEnd = 3.dp,
                            bottomEnd = 3.dp,
                            bottomStart = 12.dp,
                        ),
                        interactionSource = directionInteractions,
                        showIndication = false,
                        modifier = Modifier
                            .width(directionWidth)
                            .height(44.dp)
                            .semantics {
                                contentDescription = if (state.ascending) "Ascending" else "Descending"
                            },
                    ) {
                        SortDirectionGlyph(ascending = state.ascending)
                    }
                }
                Spacer(Modifier.width(2.dp))
                Box {
                    val activeRadius by animateDpAsState(
                        targetValue = if (sortMenuOpen) 22.dp else 12.dp,
                        animationSpec = motion.fastSpatialSpec(),
                        label = "sort active shape",
                    )
                    val joinedRadius by animateDpAsState(
                        targetValue = if (sortMenuOpen) 22.dp else 3.dp,
                        animationSpec = motion.fastSpatialSpec(),
                        label = "sort joined shape",
                    )
                    val sortContainer by animateColorAsState(
                        targetValue = if (sortMenuOpen) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        animationSpec = motion.defaultEffectsSpec(),
                        label = "sort active color",
                    )
                    val sortContent by animateColorAsState(
                        targetValue = if (sortMenuOpen) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = motion.defaultEffectsSpec(),
                        label = "sort content color",
                    )
                    KgsTooltip("Sort") {
                        KgsExpressiveSurface(
                            onClick = { sortMenuOpen = true },
                            color = sortContainer,
                            contentColor = sortContent,
                            restingCorners = KgsCornerRadii(
                                topStart = joinedRadius,
                                topEnd = activeRadius,
                                bottomEnd = activeRadius,
                                bottomStart = joinedRadius,
                            ),
                            interactionSource = sortInteractions,
                            showIndication = false,
                            modifier = Modifier
                                .width(sortWidth)
                                .height(44.dp),
                        ) {
                            AnimatedContent(
                                targetState = state.sort,
                                transitionSpec = {
                                    (fadeIn(motion.fastEffectsSpec()) +
                                        scaleIn(motion.fastSpatialSpec(), initialScale = .65f)) togetherWith
                                        (fadeOut(motion.fastEffectsSpec()) +
                                            scaleOut(motion.fastSpatialSpec(), targetScale = .65f))
                                },
                                label = "selected sort icon",
                                modifier = Modifier.semantics { contentDescription = "Sort notes" },
                            ) { sort ->
                                LibrarySortOptionGlyph(sort = sort, color = sortContent)
                            }
                        }
                    }
                    KgsBottomSheet(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                        modifier = Modifier.testTag("sort-options-menu"),
                        title = "Sort Notes",
                    ) {
                        LibrarySort.entries.forEach { sort ->
                            val selected = sort == state.sort
                            KgsTooltip(sort.label) {
                                KgsDropdownMenuItem(
                                text = sort.label,
                                selected = selected,
                                icon = {
                                    LibrarySortOptionGlyph(
                                        sort = sort,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    actions.onSortChange(sort)
                                    sortMenuOpen = false
                                },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                LibraryViewButton(
                    current = state.libraryView,
                    modifier = Modifier.width(viewWidth),
                    interactionSource = viewInteractions,
                    onClick = actions.onToggleLibraryView,
                )
            }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Box(Modifier.fillMaxSize()) {
            NoteCollection(
                notes = state.notes,
                view = state.libraryView,
                previewModes = state.previewModes,
                scrollOffset = cardScrollOffset,
                bottomPadding = 92.dp + navigationBottom,
                modifier = Modifier.noteViewportTopFade(),
                groupByDate = state.groupByDate && destination == LibraryDestination.NOTES,
                dateByCreated = state.sort == LibrarySort.LAST_CREATED,
                onScroll = onScroll,
            ) { note, cardModifier ->
                SwipeToFavoriteNote(
                    favorite = note.favorite,
                    enabled = destination != LibraryDestination.TRASH && selectedIds.isEmpty(),
                    onFavorite = { actions.onSelectedNotesAction(setOf(note.id), NoteSelectionOperation.FAVORITE, if (note.favorite) "remove" else "add") },
                    modifier = cardModifier.testTag("note-swipe-${note.id.value}"),
                ) { swipeModifier ->
                    NoteCard(
                        note = note,
                        previewMode = state.previewModes[note.id] ?: EditorMode.RICH,
                        trashed = destination == LibraryDestination.TRASH,
                        selected = note.id == selectedId,
                        highlighted = note.id in selectedIds,
                        onClick = {
                            if (selectedIds.isEmpty()) actions.onSelect(note.id) else onToggleSelection(note.id)
                        },
                        onLongClick = { onToggleSelection(note.id) },
                        cardView = state.libraryView == LibraryView.CARDS,
                        noteMorph = noteMorph,
                        modifier = swipeModifier,
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = state.notes.isEmpty(),
                enter = fadeIn(tween(180, delayMillis = 120)),
                exit = fadeOut(tween(100)),
            ) {
                EmptyLibrary(state.filter, destination, state.query,
                    Modifier.fillMaxSize().testTag("empty-library"), category = state.libraryCategory)
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    destination: LibraryDestination,
    onOpenDrawer: () -> Unit,
    onBackToNotes: () -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    if (waitForUpOrCancellation(pass = PointerEventPass.Final) != null) {
                        focusManager.clearFocus()
                    }
                }
            }
            .padding(top = statusTop + 5.dp),
    ) {
        ExpressiveIconButton(
            label = if (destination == LibraryDestination.NOTES) "Open Menu" else "Back",
            onClick = {
                keyboard?.hide()
                focusManager.clearFocus()
                if (destination == LibraryDestination.NOTES) onOpenDrawer() else onBackToNotes()
            },
        ) {
            if (destination == LibraryDestination.NOTES) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Notes")
            }
        }
        Surface(
            color = if (destination == LibraryDestination.TRASH) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            contentColor = if (destination == LibraryDestination.TRASH) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (destination == LibraryDestination.TRASH) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                } else {
                    Icon(Icons.Default.Settings, contentDescription = null)
                }
            }
        }
        Column {
            Text(
                when (destination) {
                    LibraryDestination.NOTES -> "Notes"
                    LibraryDestination.TRASH -> "Trash"
                    LibraryDestination.SETTINGS -> "Settings"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
internal fun ExpressiveIconButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = LocalKgsMotion.current.defaultEffectsSpec(),
        label = "$label active color",
    )
    KgsTooltip(label) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = background,
            restingCorners = KgsCornerRadii(20.dp),
            showIndication = false,
            modifier = modifier.size(40.dp),
        ) { content() }
    }
}

@Composable
private fun LibraryFilterButton(
    filter: LibraryFilter,
    selected: Boolean,
    pendingSyncCount: Int,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val description = when (filter) {
        LibraryFilter.ALL -> "All notes"
        LibraryFilter.FAVORITES -> "Favorite notes"
        LibraryFilter.LOCAL -> if (pendingSyncCount > 0) {
            "Local notes, $pendingSyncCount awaiting sync"
        } else {
            "Local notes"
        }
        LibraryFilter.SERVER -> "Server notes"
    }
    val motion = LocalKgsMotion.current
    val darkTheme = LocalKgsDarkTheme.current
    val accent = when (filter) {
        LibraryFilter.ALL -> MaterialTheme.colorScheme.onPrimaryContainer
        LibraryFilter.FAVORITES -> if (darkTheme) Color(0xFFFF8A9A) else FavoriteRed
        LibraryFilter.LOCAL -> if (darkTheme) Color(0xFF77D5AA) else Color(0xFF248A63)
        LibraryFilter.SERVER -> if (darkTheme) Color(0xFF9BCAFF) else Color(0xFF3978C6)
    }
    val iconColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(),
        label = "$description icon color",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected && filter == LibraryFilter.FAVORITES) favoriteContainerColor()
        else if (selected && darkTheme && filter != LibraryFilter.ALL) {
            lerp(MaterialTheme.colorScheme.surface, accent, .18f)
        } else if (selected) when (filter) {
            LibraryFilter.ALL -> MaterialTheme.colorScheme.primaryContainer
            LibraryFilter.FAVORITES -> Color(0xFFFFE9E9)
            LibraryFilter.LOCAL -> Color(0xFFE2F4E9)
            LibraryFilter.SERVER -> Color(0xFFE5EFFC)
        } else MaterialTheme.colorScheme.surface,
        animationSpec = motion.defaultEffectsSpec(),
        label = "$description container color",
    )
    val corner by animateDpAsState(
        targetValue = if (selected) 22.dp else 11.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "$description shape",
    )
    KgsTooltip(description) {
        Box {
            KgsExpressiveSurface(
                onClick = onClick,
                color = containerColor,
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                restingCorners = KgsCornerRadii(corner),
                interactionSource = interactionSource,
                showIndication = false,
                modifier = modifier
                    .height(44.dp)
                    .semantics { contentDescription = description },
            ) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (fadeIn(motion.fastEffectsSpec()) +
                            scaleIn(motion.fastSpatialSpec(), initialScale = .72f)) togetherWith
                            (fadeOut(motion.fastEffectsSpec()) +
                                scaleOut(motion.fastSpatialSpec(), targetScale = .72f))
                    },
                    label = "$description icon state",
                ) { active ->
                    when (filter) {
                        LibraryFilter.ALL -> AllNotesGlyph(iconColor)
                        LibraryFilter.FAVORITES -> Icon(
                            if (active) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = iconColor,
                        )
                        LibraryFilter.LOCAL -> LocalNotesGlyph(iconColor, filled = active)
                        LibraryFilter.SERVER -> CloudGlyph(iconColor, filled = active)
                    }
                }
            }
            if (filter == LibraryFilter.LOCAL) {
                var retainedCount by remember { mutableIntStateOf(pendingSyncCount) }
                SideEffect { if (pendingSyncCount > 0) retainedCount = pendingSyncCount }
                PendingSyncIndicator(pendingSyncCount > 0, Modifier.align(Alignment.TopEnd)) {
                    Surface(
                        color = Color(0xFFF28C28),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .testTag("pending-sync-badge")
                            .size(22.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                (pendingSyncCount.takeIf { it > 0 } ?: retainedCount).let { if (it > 9) "9+" else it.toString() },
                                style = TextStyle(fontSize = 10.sp, lineHeight = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                maxLines = 1,
                                modifier = Modifier.testTag("pending-sync-count"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingSyncIndicator(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = LocalKgsMotion.current
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    AnimatedVisibility(visibleState = visibility, modifier = modifier,
        enter = fadeIn(tween(220, easing = motion.standard)),
        exit = fadeOut(tween(220, easing = motion.standard))) {
        content()
    }
}

@Composable
private fun SortDirectionGlyph(ascending: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val motion = LocalKgsMotion.current
    val rotation by animateFloatAsState(
        targetValue = if (ascending) 180f else 0f,
        animationSpec = motion.fastSpatialSpec(),
        label = "sort direction",
    )
    Canvas(
        Modifier
            .size(20.dp)
            .graphicsLayer {
                rotationZ = rotation
            },
    ) {
        val stroke = size.minDimension * .1f
        drawLine(color, Offset(size.width * .25f, size.height * .4f), Offset(size.width * .5f, size.height * .66f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, size.height * .66f), Offset(size.width * .75f, size.height * .4f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun LibraryViewButton(
    current: LibraryView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(40.dp),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val action = if (current == LibraryView.LIST) "Card view" else "List view"
    val motion = LocalKgsMotion.current
    KgsTooltip(action) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            restingCorners = KgsCornerRadii(11.dp),
            showIndication = false,
            interactionSource = interactionSource,
            modifier = modifier.height(44.dp).semantics {
                contentDescription = action
                stateDescription = if (current == LibraryView.LIST) "List view" else "Card view"
            },
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = .84f)) togetherWith
                        (fadeOut(motion.defaultEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = .84f))
                },
                label = "Library view icon",
            ) { LibraryViewGlyph(it) }
        }
    }
}

@Composable
private fun LibraryViewGlyph(current: LibraryView) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        if (current == LibraryView.CARDS) repeat(4) { index ->
            drawRoundRect(
                color,
                topLeft = Offset(size.width * (.08f + (index % 2) * .5f), size.height * (.08f + (index / 2) * .5f)),
                size = Size(size.width * .34f, size.height * .34f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .06f),
            )
        } else repeat(2) { index ->
            drawRoundRect(color,
                topLeft = Offset(size.width * .08f, size.height * (.14f + index * .44f)),
                size = Size(size.width * .84f, size.height * .28f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .07f))
        }
    }
}

@Composable
private fun LibrarySortOptionGlyph(sort: LibrarySort, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        when (sort) {
            LibrarySort.LAST_EDITED -> {
                drawArc(
                    color = color,
                    startAngle = -55f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = Offset(size.width * .16f, size.height * .16f),
                    size = Size(size.width * .68f, size.height * .68f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(size.width * .50f, size.height * .49f), Offset(size.width * .50f, size.height * .29f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .50f, size.height * .49f), Offset(size.width * .66f, size.height * .57f), stroke, StrokeCap.Round)
            }
            LibrarySort.LAST_CREATED -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * .16f, size.height * .22f),
                    size = Size(size.width * .68f, size.height * .62f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .09f),
                    style = Stroke(stroke),
                )
                drawLine(color, Offset(size.width * .30f, size.height * .14f), Offset(size.width * .30f, size.height * .32f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .70f, size.height * .14f), Offset(size.width * .70f, size.height * .32f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .17f, size.height * .42f), Offset(size.width * .83f, size.height * .42f), stroke, StrokeCap.Round)
            }
            LibrarySort.ALPHABETICAL -> {
                drawLine(color, Offset(size.width * .22f, size.height * .78f), Offset(size.width * .38f, size.height * .22f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .38f, size.height * .22f), Offset(size.width * .54f, size.height * .78f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .28f, size.height * .58f), Offset(size.width * .48f, size.height * .58f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .63f, size.height * .27f), Offset(size.width * .82f, size.height * .27f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .82f, size.height * .27f), Offset(size.width * .63f, size.height * .73f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .63f, size.height * .73f), Offset(size.width * .82f, size.height * .73f), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun AllNotesGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .075f
        drawRoundRect(color, topLeft = Offset(size.width * .16f, size.height * .10f),
            size = Size(size.width * .68f, size.height * .80f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .14f),
            style = Stroke(stroke))
        repeat(3) { row ->
            val y = size.height * (.30f + row * .20f)
            drawLine(color, Offset(size.width * .32f, y), Offset(size.width * .68f, y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
internal fun LocalNotesGlyph(color: Color, filled: Boolean = false) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .08f
        drawRoundRect(
            color,
            topLeft = Offset(size.width * .25f, size.height * .08f),
            size = Size(size.width * .5f, size.height * .84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .1f),
            style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke),
        )
        drawCircle(
            if (filled) Color.White else color,
            stroke * .65f,
            Offset(size.width * .5f, size.height * .8f),
        )
    }
}

@Composable
internal fun CloudGlyph(color: Color, filled: Boolean = false) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .085f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .22f, size.height * .7f)
            cubicTo(size.width * .06f, size.height * .7f, size.width * .06f, size.height * .44f, size.width * .25f, size.height * .43f)
            cubicTo(size.width * .32f, size.height * .18f, size.width * .68f, size.height * .18f, size.width * .74f, size.height * .45f)
            cubicTo(size.width * .94f, size.height * .46f, size.width * .95f, size.height * .7f, size.width * .78f, size.height * .7f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun PrivacyGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .09f
        val shield = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .5f, size.height * .08f)
            lineTo(size.width * .82f, size.height * .22f)
            lineTo(size.width * .78f, size.height * .58f)
            cubicTo(size.width * .74f, size.height * .78f, size.width * .58f, size.height * .9f, size.width * .5f, size.height * .94f)
            cubicTo(size.width * .42f, size.height * .9f, size.width * .26f, size.height * .78f, size.width * .22f, size.height * .58f)
            lineTo(size.width * .18f, size.height * .22f)
            close()
        }
        drawPath(shield, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(
            color,
            Offset(size.width * .36f, size.height * .51f),
            Offset(size.width * .47f, size.height * .63f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * .47f, size.height * .63f),
            Offset(size.width * .68f, size.height * .4f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
internal fun QuestionGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .085f
        drawCircle(
            color = color,
            radius = size.minDimension * .4f,
            center = center,
            style = Stroke(stroke),
        )
        drawArc(
            color = color,
            startAngle = 205f,
            sweepAngle = 220f,
            useCenter = false,
            topLeft = Offset(size.width * .34f, size.height * .24f),
            size = Size(size.width * .32f, size.height * .34f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color,
            Offset(size.width * .5f, size.height * .57f),
            Offset(size.width * .5f, size.height * .64f),
            stroke,
            StrokeCap.Round,
        )
        drawCircle(color, stroke * .55f, Offset(size.width * .5f, size.height * .75f))
    }
}

@Composable
private fun ImageGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .09f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * .09f, size.height * .14f),
            size = Size(size.width * .82f, size.height * .72f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .12f),
            style = Stroke(stroke),
        )
        drawCircle(color, stroke * .72f, Offset(size.width * .68f, size.height * .36f))
        drawLine(color, Offset(size.width * .17f, size.height * .76f), Offset(size.width * .42f, size.height * .49f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .42f, size.height * .49f), Offset(size.width * .58f, size.height * .66f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .58f, size.height * .66f), Offset(size.width * .72f, size.height * .53f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .72f, size.height * .53f), Offset(size.width * .86f, size.height * .71f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun LibraryCategoryHeader(category: String, count: Int, filterBaseWidth: androidx.compose.ui.unit.Dp) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) {}.testTag("library-category-context"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(filterBaseWidth).testTag("library-category-icon"), contentAlignment = Alignment.Center) {
            CategoryIcon(false, LocalKgsAccentColors.current.focus)
        }
        Text(category.ifBlank { "No Category" }, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
            Text("$count ${if (count == 1) "Note" else "Notes"}", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun DrawerSyncButton(syncing: Boolean, onSync: () -> Unit) {
    KgsTooltip("Sync all Sources") {
        KgsExpressiveSurface(
            onClick = onSync,
            enabled = !syncing,
            showIndication = false,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            restingCorners = KgsCornerRadii(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("sidebar-sync"),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                AnimatedContent(targetState = syncing,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    label = "Sidebar sync progress") { busy ->
                    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(19.dp).semantics { contentDescription = "Syncing all Sources" },
                            strokeWidth = 2.dp, color = LocalKgsAccentColors.current.focus)
                        else Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
                Text("Sync", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun NotesDrawer(
    visible: Boolean,
    state: NotesUiState,
    onDismiss: () -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onSync: () -> Unit,
    onDestination: (LibraryDestination) -> Unit,
    onCategory: (String?) -> Unit,
    onClosed: () -> Unit,
) {
    val motion = LocalKgsMotion.current
    val closed by rememberUpdatedState(onClosed)
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) motion.defaultSpatialSpec() else tween(220, easing = motion.standard),
        label = "drawer slide",
        finishedListener = { if (!visible && it == 0f) closed() },
    )
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val drawerWidth = 292.dp
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val effectiveProgress = (progress + dragOffset / drawerWidthPx).coerceIn(0f, 1f)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        if (effectiveProgress > .01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .34f * effectiveProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        if (effectiveProgress > .001f) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
                shadowElevation = 0.dp,
                modifier = Modifier
                    .testTag("notes-drawer")
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset {
                        IntOffset(
                            x = (-drawerWidthPx * (1f - effectiveProgress)).roundToInt(),
                            y = 0,
                        )
                    }
                    .pointerInput(visible) {
                        detectHorizontalDragGestures(
                            onDragCancel = { dragOffset = 0f },
                            onDragEnd = {
                                if (effectiveProgress < .72f) onDismiss()
                                dragOffset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            dragOffset = (dragOffset + amount).coerceIn(-drawerWidthPx, 0f)
                        }
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = 18.dp,
                            top = statusTop + 18.dp,
                            end = 18.dp,
                            bottom = navigationBottom + 14.dp,
                        ),
                ) {
                    SidebarLogo()
                    Spacer(Modifier.height(24.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("sidebar-locations"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "sources-heading") { DrawerSectionTitle("Sources") }
                        items(state.sources, key = { "source:${it.id.value}" }) { source ->
                            DrawerRow(
                                label = source.name,
                                selected = source.visible,
                                onClick = { onToggleSource(source.id) },
                                icon = {
                                    if (source.id == LocalSourceId) {
                                        LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                            )
                        }
                        item(key = "sync") {
                            DrawerSyncButton(state.syncingSources, onSync)
                        }
                        item(key = "notes-heading") { DrawerSectionTitle("Notes", topSpace = true) }
                        item(key = "all-notes") {
                            DrawerRow("All Notes", selected = state.destination == LibraryDestination.NOTES && state.libraryCategory == null,
                                onClick = { onCategory(null) }, navigation = true, testTag = "sidebar-all-notes",
                                icon = { AllNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant) })
                        }
                        item(key = "no-category") {
                            DrawerRow("No Category", selected = state.destination == LibraryDestination.NOTES && state.libraryCategory == "",
                                onClick = { onCategory("") }, navigation = true, testTag = "sidebar-no-category",
                                icon = { CategoryIcon(false, MaterialTheme.colorScheme.onSurfaceVariant) })
                        }
                        items(state.categories.filter(String::isNotBlank).distinct().sortedBy(String::lowercase), key = { "category:$it" }) { category ->
                            DrawerRow(category, selected = state.destination == LibraryDestination.NOTES && state.libraryCategory == category,
                                onClick = { onCategory(category) }, navigation = true, testTag = "sidebar-category-$category",
                                icon = { CategoryIcon(false, MaterialTheme.colorScheme.onSurfaceVariant) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DrawerRow(
                        label = "Trash",
                        selected = state.destination == LibraryDestination.TRASH,
                        onClick = { onDestination(LibraryDestination.TRASH) },
                        navigation = true,
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    )
                    Spacer(Modifier.height(4.dp))
                    DrawerRow(
                        label = "Settings",
                        selected = state.destination == LibraryDestination.SETTINGS,
                        onClick = { onDestination(LibraryDestination.SETTINGS) },
                        navigation = true,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionTitle(label: String, topSpace: Boolean = false) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (topSpace) 12.dp else 0.dp, start = 4.dp).semantics { heading() })
}

@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    navigation: Boolean = false,
    testTag: String? = null,
    icon: @Composable () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (selected) {
            if (navigation) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        } else Color.Transparent,
        animationSpec = LocalKgsMotion.current.defaultEffectsSpec(),
        label = "Sidebar row highlight",
    )
    KgsTooltip(label) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = color,
            contentColor = if (selected && navigation) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            restingCorners = KgsCornerRadii(14.dp),
            showIndication = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag ?: "modal-choice-$label")
                .semantics { this.selected = selected },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            ) {
                Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) { icon() }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(if (testTag == null) "modal-choice-label-$label" else "$testTag-label"),
                )
                if (navigation) {
                    if (selected) Box(Modifier.size(7.dp).background(LocalKgsAccentColors.current.focus, CircleShape))
                    else Canvas(Modifier.size(18.dp)) {
                        val color = Color.Gray
                        val a = Offset(size.width * .38f, size.height * .28f)
                        val b = Offset(size.width * .62f, size.height * .5f)
                        val c = Offset(size.width * .38f, size.height * .72f)
                        drawLine(color, a, b, 1.6.dp.toPx(), StrokeCap.Round)
                        drawLine(color, b, c, 1.6.dp.toPx(), StrokeCap.Round)
                    }
                } else if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteSummary,
    previewMode: EditorMode,
    trashed: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cardView: Boolean = false,
    noteMorph: NoteMorph? = null,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val previewMarkdown = note.previewMarkdown.ifBlank { note.snippet }
    val linkColor = MaterialTheme.colorScheme.primary
    val preview = remember(previewMarkdown, previewMode, linkColor) {
        noteCardPreview(previewMarkdown, previewMode, linkColor)
    }
    val motion = LocalKgsMotion.current
    val container by animateColorAsState(
        targetValue = when {
            highlighted -> lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, .55f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "Note selection background",
    )
    val timestampColor by animateColorAsState(
        if (highlighted || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = motion.defaultEffectsSpec(), label = "Timestamp selection background",
    )
    val timestampTextColor by animateColorAsState(
        if (highlighted || selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motion.defaultEffectsSpec(), label = "Timestamp selection text",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            highlighted -> MaterialTheme.colorScheme.primary
            trashed -> if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFD2CECA) else MaterialTheme.colorScheme.outline
            else -> Color.Transparent
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "Note selection border",
    )
    val corner by animateDpAsState(
        targetValue = if (highlighted) 24.dp else 14.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "Note selection corner",
    )
    val surfaceCorner = noteMorphCorner(noteMorph, corner)
    KgsExpressiveSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        color = container,
        contentColor = if (trashed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        restingCorners = KgsCornerRadii(surfaceCorner),
        showIndication = false,
        modifier = modifier
            .fillMaxWidth()
            .noteMorphBounds(noteMorph, note.id, "container", corner, 0.dp)
            .border(2.dp, borderColor, RoundedCornerShape(surfaceCorner))
            .testTag("note-card-${note.id.value}")
            .semantics { contentDescription = if (highlighted) "Selected Note" else "Note" },
    ) {
        Box(Modifier.fillMaxSize().noteMorphStableLayout(noteMorph)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 17.dp, top = 13.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = note.title,
                        style = noteMorphTitleStyle(noteMorph, editor = false),
                        color = if (trashed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth()
                            .noteMorphBounds(noteMorph, note.id, "title")
                            .kgsHorizontalTextFade(end = 22.dp)
                            .testTag("note-title-${note.id.value}"),
                    )
                    PendingSyncIndicator(note.sourceId != LocalSourceId && note.syncState in pendingSyncStates,
                        Modifier.align(Alignment.CenterEnd)) {
                        Box(Modifier.background(container)
                            .padding(start = 4.dp, end = 10.dp)) {
                            Surface(color = Color(0xFFF28C28), shape = CircleShape,
                                modifier = Modifier.size(10.dp).testTag("note-pending-sync-${note.id.value}")
                                    .semantics { contentDescription = "Awaiting sync" }) {}
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().padding(end = 17.dp)
                        .weight(1f)
                        .noteMorphBounds(noteMorph, note.id, "body")
                        .notePreviewBottomFade(if (cardView) 38.dp else 28.dp)
                        .testTag("note-preview-${note.id.value}"),
                ) {
                    if (preview.isNotEmpty()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (trashed) .72f else 1f),
                            overflow = TextOverflow.Clip,
                            maxLines = 32,
                            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
                        )
                    } else {
                        Spacer(Modifier.height(NoteCardFooterHeight))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NoteCardFooterHeight)
                    .noteMorphFooter(noteMorph),
            ) {
                // This slot owns all remaining width, keeping the timestamp anchored at the edge.
                Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    if (note.favorite) {
                        Box(
                            Modifier.fillMaxHeight().background(container)
                                .padding(start = 17.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "Favorite",
                                tint = if (trashed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f) else FavoriteRed,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Box(
                    Modifier.fillMaxHeight()
                        .testTag("note-timestamp-backing-${note.id.value}")
                        .background(container, RoundedCornerShape(topStart = 18.dp))
                        .padding(start = 6.dp, top = 6.dp, end = 11.dp, bottom = 8.dp),
                ) {
                    Surface(
                        color = timestampColor,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(28.dp).testTag("note-timestamp-${note.id.value}"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                remember(note.updatedAt, context) {
                                    val updated = Date.from(note.updatedAt)
                                    val zone = java.time.ZoneId.systemDefault()
                                    if (note.updatedAt.atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)) {
                                        android.text.format.DateFormat.getTimeFormat(context).format(updated)
                                    } else {
                                        android.text.format.DateFormat.getDateFormat(context).format(updated)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = timestampTextColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fixed length, always present, so spring overshoot never reveals a hard text edge. */
private fun Modifier.notePreviewBottomFade(height: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadeHeight = height.toPx().coerceAtMost(size.height)
        if (fadeHeight > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.White, .65f to Color.White.copy(alpha = .18f), 1f to Color.Transparent),
                    startY = size.height - fadeHeight,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun Modifier.noteViewportTopFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White),
                startY = 0f,
                endY = 12.dp.toPx(),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private enum class SelectionPanel { NONE, RENAME, CATEGORY, SOURCE }
private val LocalSelectionActionWidth = staticCompositionLocalOf { 40.dp }

@Composable
private fun NoteSelectionBar(
    expansion: KgsSheetExpansion,
    count: Int,
    selectedTitles: List<String>,
    selectedTitle: String,
    allFavorite: Boolean,
    moveStatus: EditorSaveStatus,
    currentCategory: String?,
    currentSource: SourceId?,
    destination: LibraryDestination,
    categories: List<String>,
    sources: List<SourceChoice>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onAction: (NoteSelectionOperation, String) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    var panel by remember { mutableStateOf(SelectionPanel.NONE) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    val motion = LocalKgsMotion.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val renameFraction by animateFloatAsState(
        if (count == 1 && destination != LibraryDestination.TRASH) 1f else 0f,
        tween(260, easing = motion.standard), label = "Rename action availability",
    )
    var title by remember(selectedTitle) { mutableStateOf(selectedTitle) }
    LaunchedEffect(expansion.expanded, panel) {
        if (!expansion.expanded) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(count, panel) {
        expansion.available = panel != SelectionPanel.NONE && (panel != SelectionPanel.RENAME || count == 1)
        if (!expansion.available) expansion.expanded = false
    }
    fun togglePanel(next: SelectionPanel) {
        expansion.available = true
        expansion.expanded = panel != next || !expansion.expanded
        panel = next
    }
    BackHandler(enabled = interactive && expansion.expanded) { expansion.expanded = false }
    CompositionLocalProvider(LocalKgsInlineSheet provides true) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 0.dp,
            modifier = modifier
                .fillMaxWidth()
                .testTag("note-selection-bar"),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    KgsExpressiveSurface(
                        onClick = onDismiss,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        showIndication = false,
                        restingCorners = KgsCornerRadii(16.dp),
                        modifier = Modifier
                            .width(72.dp)
                            .height(40.dp)
                            .semantics { contentDescription = "Done" },
                    ) {
                        Text("Done", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(14.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val slotWidth = maxWidth / ((if (destination == LibraryDestination.TRASH) 2f else 4f) + renameFraction)
                    CompositionLocalProvider(LocalSelectionActionWidth provides slotWidth) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Interpolate slot widths together so removing an action
                            // never changes the remaining gaps on the final frame.
                            Box(
                                Modifier.width(slotWidth * renameFraction).clipToBounds()
                                    .graphicsLayer { alpha = renameFraction }
                                    .then(if (renameFraction <= .001f) Modifier.clearAndSetSemantics {} else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.requiredWidth(slotWidth), contentAlignment = Alignment.Center) {
                                    SelectionActionButton("Rename", { if (count == 1) togglePanel(SelectionPanel.RENAME) }) {
                                        Icon(Icons.Default.Edit, contentDescription = null)
                                    }
                                }
                            }
                            if (destination == LibraryDestination.TRASH) {
                                SelectionActionButton("Restore", { onAction(NoteSelectionOperation.RESTORE, "") }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                                SelectionActionButton("Delete permanently", { deleteConfirmationOpen = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = DestructiveRed)
                                }
                            } else {
                                SelectionActionButton(if (allFavorite) "Unfavourite" else "Favourite", {
                                    onAction(NoteSelectionOperation.FAVORITE, if (allFavorite) "remove" else "add")
                                }) {
                                    Icon(
                                        if (allFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = FavoriteRed,
                                    )
                                }
                                SelectionActionButton("Category", { togglePanel(SelectionPanel.CATEGORY) }) {
                                    CategoryIcon(false, MaterialTheme.colorScheme.onSurface)
                                }
                                SelectionActionButton("Location", { togglePanel(SelectionPanel.SOURCE) }) {
                                    SaveStatusGlyph(moveStatus)
                                }
                                SelectionActionButton("Delete", { deleteConfirmationOpen = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = DestructiveRed)
                                }
                            }
                        }
                    }
                }
            }
        }
        KgsExpandableSheetPanel(expansion) {
            if (panel != SelectionPanel.NONE) {
                AnimatedContent(
                    targetState = panel,
                    contentAlignment = Alignment.BottomStart,
                    transitionSpec = {
                        (fadeIn(tween(220, easing = motion.standard)) + slideInVertically(tween(300, easing = motion.standard)) { it / 8 }) togetherWith
                            (fadeOut(tween(160, easing = motion.standard)) + slideOutVertically(tween(300, easing = motion.standard)) { it / 8 }) using
                            SizeTransform(clip = true) { _, _ -> tween(300, easing = motion.standard) }
                    },
                    label = "Selected Note menu",
                    modifier = Modifier.fillMaxWidth(),
                ) { shownPanel ->
                    when (shownPanel) {
                        SelectionPanel.RENAME -> KgsSheetDialog(
                            expanded = true,
                            onDismissRequest = { expansion.expanded = false },
                            title = { Text("Rename Note") },
                            text = {
                                KgsTextField(
                                    value = title,
                                    onValueChange = { title = it },
                                    modifier = Modifier.testTag("rename-note-field"),
                                )
                            },
                            confirmButton = {
                                SelectionTextButton("Rename", primary = true, enabled = interactive && count == 1 && title.isNotBlank() && expansion.expanded && panel == shownPanel, onClick = {
                                    onRename(title)
                                    expansion.expanded = false
                                })
                            },
                            dismissButton = { SelectionTextButton("Cancel", onClick = { expansion.expanded = false }) },
                        )
                        SelectionPanel.CATEGORY -> CategoryDialog(
                            expanded = true,
                            current = currentCategory,
                            categories = categories,
                            onSelect = { onAction(NoteSelectionOperation.CATEGORY, it); expansion.expanded = false },
                            onDismiss = { expansion.expanded = false },
                        )
                        SelectionPanel.SOURCE -> SourceDialog(
                            expanded = true,
                            current = currentSource,
                            sources = sources,
                            onSelect = { onAction(NoteSelectionOperation.MOVE, it.value); expansion.expanded = false },
                            onDismiss = { expansion.expanded = false },
                        )
                        SelectionPanel.NONE -> Spacer(Modifier.fillMaxWidth().height(0.dp))
                    }
                }
            }
        }
    }
    NoteDeleteConfirmationDialog(
        expanded = deleteConfirmationOpen && interactive,
        permanently = destination == LibraryDestination.TRASH,
        titles = selectedTitles,
        onConfirm = {
            if (interactive) {
                onAction(if (destination == LibraryDestination.TRASH) NoteSelectionOperation.DELETE else NoteSelectionOperation.TRASH, "")
                onDismiss()
            }
            deleteConfirmationOpen = false
        },
        onDismiss = { deleteConfirmationOpen = false },
    )
}

@Composable
private fun SelectionTextButton(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    KgsExpressiveSurface(
        onClick = onClick,
        enabled = enabled,
        color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        showIndication = false,
        restingCorners = KgsCornerRadii(14.dp),
        modifier = Modifier.height(44.dp).widthIn(min = 96.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp)) }
}

@Composable
private fun SelectionActionButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(LocalSelectionActionWidth.current), contentAlignment = Alignment.Center) {
        KgsTooltip(label) {
            KgsExpressiveSurface(
                onClick = onClick,
                color = Color.Transparent,
                showIndication = false,
                restingCorners = KgsCornerRadii(16.dp),
                modifier = Modifier.size(40.dp)
                    .semantics { contentDescription = label },
            ) { content() }
        }
    }
}

@Composable
private fun NoteDeleteConfirmationDialog(
    expanded: Boolean,
    permanently: Boolean,
    titles: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var displayed by remember { mutableStateOf(permanently to titles) }
    if (expanded) displayed = permanently to titles
    val shownPermanently = displayed.first
    val shownTitles = displayed.second
    KgsConfirmationDialog(expanded = expanded, onDismissRequest = onDismiss) {

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(44.dp).background(DestructiveRed.copy(alpha = .1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = DestructiveRed)
                }
                Text(
                    if (shownPermanently) "Delete permanently?" else "Move to Trash?",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (shownPermanently) {
                        if (shownTitles.size == 1) "This Note cannot be restored after deletion."
                        else "These Notes cannot be restored after deletion."
                    } else {
                        if (shownTitles.size == 1) "You can restore this Note from Trash later."
                        else "You can restore these Notes from Trash later."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${shownTitles.size} ${if (shownTitles.size == 1) "Note" else "Notes"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("delete-note-titles"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shownTitles.size) { index ->
                            Text(
                                shownTitles[index].ifBlank { "Untitled Note" },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("delete-note-title-$index"),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = onConfirm,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = DestructiveRed),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("confirm-note-delete"),
                    ) { Text(if (shownPermanently) "Delete" else "Move to Trash", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}


@Composable
private fun EmptyLibrary(
    filter: LibraryFilter,
    destination: LibraryDestination,
    query: String,
    modifier: Modifier,
    category: String? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✎", style = MaterialTheme.typography.displaySmall)
                }
            }
            Text(
                text = when {
                    query.isNotBlank() -> "No matching notes"
                    destination == LibraryDestination.TRASH -> "Trash is empty"
                    filter == LibraryFilter.FAVORITES -> "No favorites yet"
                    filter == LibraryFilter.LOCAL -> "No local notes"
                    filter == LibraryFilter.SERVER -> "No synced server notes"
                    category == "" -> "No Notes without a Category"
                    category != null -> "No Notes in this Category"
                    else -> "A quiet place for your thoughts"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    query.isNotBlank() -> "Try another search term."
                    destination == LibraryDestination.TRASH -> "Deleted notes stay recoverable here."
                    filter == LibraryFilter.SERVER -> "Synced notes from connected Sources appear here."
                    category == "" -> "Notes without an assigned Category appear here."
                    category != null -> "New Notes you create here will use this Category."
                    else -> "Create a note and it will be stored as portable Markdown."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun EditorPane(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
    modifier: Modifier,
    noteMorph: NoteMorph? = null,
    stableLayoutScope: SharedTransitionScope? = noteMorph?.scope,
) {
    val fallbackDock = remember { KgsSheetDock() }
    val dock = LocalKgsSheetDock.current ?: fallbackDock
    CompositionLocalProvider(LocalKgsSheetDock provides dock) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .then(if (stableLayoutScope == null) Modifier else with(stableLayoutScope) { Modifier.skipToLookaheadSize() })
            .testTag("note-editor"),
    ) {
        EditorHeader(state, note, actions, showBack, noteMorph)
        if (note.state == NoteState.TRASHED) {
            TrashedNote(note, actions, Modifier.fillMaxSize())
        } else {
            KgsMarkdownEditor(
                markdown = note.markdown,
                requestedMode = state.editorMode,
                onMarkdownChange = actions.onContentChange,
                onModeChange = actions.onEditorModeChange,
                onAddImage = actions.onAddImage,
                canAddImage = state.sources.firstOrNull { it.id == note.sourceId }?.supportsAttachments
                    ?: (note.sourceId == LocalSourceId),
                openManagedAttachment = actions.openManagedAttachment,
                modifier = Modifier.fillMaxSize(),
                bodyModifier = Modifier.noteMorphBounds(noteMorph, note.id, "body"),
                richLoadingContent = {
                    // An empty hint shares the editor's body metrics. Formatted
                    // content is drawn only by the actual Rich Mode renderer.
                    if (note.markdown.isBlank()) {
                        var hintVisible by remember(note.id) { mutableStateOf(false) }
                        LaunchedEffect(note.id) { hintVisible = true }
                        val hintAlpha by animateFloatAsState(if (hintVisible) 1f else 0f, tween(180), label = "Empty Note hint")
                        Text("Start writing…", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).graphicsLayer { alpha = hintAlpha })
                    }
                },
            )
        }
    }
    }
}

@Composable
private fun EditorHeader(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
    noteMorph: NoteMorph? = null,
) {
    var categoryDialogOpen by remember { mutableStateOf(false) }
    var sourceDialogOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    val dismissSheets = rememberKgsDismissSheets()
    fun toggleSheet(open: Boolean, setOpen: (Boolean) -> Unit) {
        dismissSheets()
        setOpen(!open)
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.kgsSheetControls(LocalKgsSheetDock.current).padding(start = 6.dp, top = statusTop + 2.dp, end = 6.dp, bottom = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (showBack) {
                ExpressiveIconButton(label = "Back", onClick = actions.onCloseEditor, modifier = Modifier.zIndex(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Notes",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            KgsNoteTitleField(
                value = note.title,
                onValueChange = actions.onRename,
                textStyle = noteMorphTitleStyle(noteMorph, editor = true),
                height = 40.dp,
                modifier = Modifier
                    .weight(1f)
                    .editorTitleBackOverlap(if (showBack) 6.dp else 0.dp)
                    .noteMorphBounds(noteMorph, note.id, "title")
                    .testTag("editor-title"),
            )
            SaveStatusIndicator(
                status = when {
                    state.changingSource -> EditorSaveStatus.SYNCING
                    note.syncState == NoteSyncState.SYNCING -> EditorSaveStatus.SYNCING
                    state.saving -> EditorSaveStatus.SAVING
                    else -> noteSaveStatus(note.syncState)
                },
                onClick = { toggleSheet(sourceDialogOpen) { sourceDialogOpen = it } },
            )
            if (note.state != NoteState.TRASHED) {
                KgsTooltip("Category") {
                    KgsExpressiveSurface(
                        onClick = { toggleSheet(categoryDialogOpen) { categoryDialogOpen = it } },
                        restingCorners = KgsCornerRadii(12.dp),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics {
                                contentDescription = if (note.category.isBlank()) {
                                    "Choose Category"
                                } else {
                                    "Choose Category: ${note.category}"
                                }
                            },
                    ) {
                        CategoryIcon(
                            filled = note.category.isNotBlank(),
                            color = if (note.category.isNotBlank()) {
                                LocalKgsAccentColors.current.focus
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Box {
                    ExpressiveIconButton(
                        label = "More",
                        onClick = { toggleSheet(actionsMenuOpen) { actionsMenuOpen = it } },
                        selected = actionsMenuOpen,
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More note actions",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    KgsBottomSheet(
                        expanded = actionsMenuOpen,
                        title = "Note actions",
                        onDismissRequest = { actionsMenuOpen = false },
                    ) {
                        val favoriteLabel = if (note.favorite) "Unfavourite" else "Favourite"
                        KgsTooltip(favoriteLabel) {
                            KgsDropdownMenuItem(
                                text = favoriteLabel,
                                icon = {
                                    Icon(
                                        if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (note.favorite) FavoriteRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    actions.onToggleFavorite()
                                    actionsMenuOpen = false
                                },
                            )
                        }
                        KgsTooltip("Delete") {
                            KgsDropdownMenuItem(
                                text = "Delete",
                                contentColor = DestructiveRed,
                                icon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = DestructiveRed,
                                    )
                                },
                                onClick = {
                                    actionsMenuOpen = false
                                    deleteConfirmationOpen = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    CategoryDialog(
        expanded = categoryDialogOpen,
        current = note.category,
        categories = state.categories,
        onSelect = {
            actions.onCategoryChange(it)
            categoryDialogOpen = false
        },
        onDismiss = { categoryDialogOpen = false },
    )

    SourceDialog(
        expanded = sourceDialogOpen,
        current = note.sourceId,
        sources = state.sources,
        onSelect = {
            actions.onSourceChange(it)
            sourceDialogOpen = false
        },
        onDismiss = { sourceDialogOpen = false },
    )

    NoteDeleteConfirmationDialog(
        expanded = deleteConfirmationOpen,
        permanently = false,
        titles = listOf(note.title),
        onConfirm = {
            deleteConfirmationOpen = false
            actions.onMoveToTrash()
        },
        onDismiss = { deleteConfirmationOpen = false },
    )

}

@Composable
private fun CategoryDialog(
    expanded: Boolean,
    current: String?,
    categories: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    KgsBottomSheet(expanded = expanded, onDismissRequest = onDismiss, title = "Choose Category") {
        var query by rememberSaveable { mutableStateOf("") }
        var creating by rememberSaveable { mutableStateOf(false) }
        var name by rememberSaveable { mutableStateOf("") }
        val nameFocus = remember { FocusRequester() }
        val candidate = query.trim()

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category-dialog"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                if (creating) {
                    LaunchedEffect(Unit) { nameFocus.requestFocus() }
                    Text("New Category", style = MaterialTheme.typography.titleLarge)
                    KgsTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Category name",
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("new-category-name"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = rememberKgsHapticClick { creating = false }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = name.isNotBlank() && categories.none { it.equals(name.trim(), ignoreCase = true) },
                            onClick = rememberKgsHapticClick { onSelect(name.trim()) },
                        ) { Text("Create") }
                    }
                } else {
                    Text("Choose Category", style = MaterialTheme.typography.titleLarge)
                    KgsSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search Categories",
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("category-search"),
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.heightIn(max = (LocalKgsSheetContentHeight.current - 220.dp).coerceAtLeast(120.dp)),
                    ) {
                        item {
                            ModalChoiceRow(
                                label = "No Category",
                                selected = current?.isBlank() == true,
                                onClick = { onSelect("") },
                                icon = {
                                    CategoryIcon(false, MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                            )
                        }
                        items(
                            items = (categories + listOfNotNull(current)).filter(String::isNotBlank).distinct()
                                .filter { it.contains(candidate, ignoreCase = true) },
                            key = { it },
                        ) { category ->
                            ModalChoiceRow(
                                label = category,
                                selected = category == current,
                                onClick = { onSelect(category) },
                                icon = {
                                    CategoryIcon(
                                        filled = category == current,
                                        color = if (category == current) {
                                            LocalKgsAccentColors.current.focus
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                            )
                        }
                    }
                    KgsTooltip("Create Category") {
                        Button(
                            enabled = candidate.isBlank() || categories.none { it.equals(candidate, ignoreCase = true) },
                            onClick = rememberKgsHapticClick {
                                if (candidate.isNotBlank()) onSelect(candidate)
                                else { name = ""; creating = true }
                            },
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (candidate.isBlank()) "New Category" else "Create “$candidate”")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceDialog(
    expanded: Boolean,
    current: SourceId?,
    sources: List<SourceChoice>,
    onSelect: (SourceId) -> Unit,
    onDismiss: () -> Unit,
    explanation: String = "The Working Copy stays available offline.",
) {
    KgsBottomSheet(expanded = expanded, onDismissRequest = onDismiss, title = "Save Note to") {

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("source-dialog"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text("Save Note to", style = MaterialTheme.typography.titleLarge)
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                sources.forEach { source ->
                    ModalChoiceRow(
                        label = source.name,
                        selected = source.id == current,
                        onClick = { onSelect(source.id) },
                        icon = {
                            if (source.id == LocalSourceId) {
                                LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModalChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    KgsTooltip(label) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            restingCorners = KgsCornerRadii(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("single-choice-$label")
                .semantics { role = Role.RadioButton; this.selected = selected },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(20.dp)) {
                    val indicator = if (selected) contentColor else contentColor.copy(alpha = .45f)
                    drawCircle(indicator, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    if (selected) drawCircle(indicator, radius = 5.dp.toPx())
                }
            }
        }
    }
}

private enum class EditorSaveStatus(val description: String) {
    SAVING("Saving"),
    SAVED_LOCALLY("Saved locally"),
    SYNCING("Saved locally, syncing"),
    SYNCED("Synced"),
    NEEDS_ATTENTION("Needs attention"),
}

private fun noteSaveStatus(syncState: NoteSyncState): EditorSaveStatus = when (syncState) {
    NoteSyncState.SYNCING -> EditorSaveStatus.SYNCING
    NoteSyncState.NEEDS_ATTENTION -> EditorSaveStatus.NEEDS_ATTENTION
    NoteSyncState.SYNCED -> EditorSaveStatus.SYNCED
    else -> EditorSaveStatus.SAVED_LOCALLY
}

@Composable
private fun SaveStatusIndicator(status: EditorSaveStatus, onClick: () -> Unit) {
    KgsTooltip("Location") {
        KgsExpressiveSurface(
            onClick = onClick,
            restingCorners = KgsCornerRadii(19.dp),
            modifier = Modifier
                .size(38.dp)
                .semantics { contentDescription = status.description },
        ) {
            SaveStatusGlyph(status)
        }
    }
}

@Composable
private fun SaveStatusGlyph(status: EditorSaveStatus) {
    val motion = LocalKgsMotion.current
    val blue = Color(0xFF2878D0)
    val orange = Color(0xFFF28C28)
    val green = Color(0xFF2E9B62)
    val loading = status == EditorSaveStatus.SAVING || status == EditorSaveStatus.SYNCING
    val color by animateColorAsState(
        targetValue = when (status) {
            EditorSaveStatus.SAVING, EditorSaveStatus.NEEDS_ATTENTION -> orange
            EditorSaveStatus.SAVED_LOCALLY, EditorSaveStatus.SYNCING -> blue
            EditorSaveStatus.SYNCED -> green
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "save status color",
    )
    val glyphProgress by animateFloatAsState(
        targetValue = if (loading) 0f else 1f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "save status glyph",
    )
    val sweep by animateFloatAsState(
        targetValue = if (loading) 270f else 360f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "save status sweep",
    )
    val infinite = rememberInfiniteTransition(label = "saving rotation")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "saving rotation",
    )

    Canvas(Modifier.size(24.dp)) {
        val stroke = size.minDimension * .09f
        val inset = stroke / 2
        rotate(if (loading) rotation else 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        when (status) {
            EditorSaveStatus.SAVING, EditorSaveStatus.SYNCING -> Unit
            EditorSaveStatus.SAVED_LOCALLY -> {
                val glyph = blue.copy(alpha = glyphProgress.coerceIn(0f, 1f))
                val scale = .72f + .28f * glyphProgress
                val left = size.width * (.5f - .19f * scale)
                val top = size.height * (.5f - .27f * scale)
                drawRoundRect(
                    glyph,
                    topLeft = Offset(left, top),
                    size = Size(size.width * .38f * scale, size.height * .54f * scale),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .07f),
                    style = Stroke(stroke * .78f),
                )
                drawCircle(glyph, stroke * .32f, Offset(size.width * .5f, size.height * (.5f + .19f * scale)))
            }
            EditorSaveStatus.NEEDS_ATTENTION -> {
                drawLine(color, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .5f, size.height * .55f), stroke, StrokeCap.Round)
                drawCircle(color, stroke * .6f, Offset(size.width * .5f, size.height * .7f))
            }
            EditorSaveStatus.SYNCED -> {
                val glyph = green.copy(alpha = glyphProgress.coerceIn(0f, 1f))
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .27f, size.height * .64f)
                    cubicTo(size.width * .13f, size.height * .64f, size.width * .13f, size.height * .44f, size.width * .3f, size.height * .43f)
                    cubicTo(size.width * .36f, size.height * .22f, size.width * .66f, size.height * .24f, size.width * .7f, size.height * .46f)
                    cubicTo(size.width * .86f, size.height * .47f, size.width * .86f, size.height * .65f, size.width * .72f, size.height * .65f)
                    close()
                }
                drawPath(path, glyph, style = Stroke(stroke * .78f, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun CategoryIcon(filled: Boolean, color: Color) {
    Canvas(Modifier.size(24.dp).padding(2.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .1f, size.height * .31f)
            quadraticTo(size.width * .1f, size.height * .2f, size.width * .22f, size.height * .2f)
            lineTo(size.width * .4f, size.height * .2f)
            quadraticTo(size.width * .47f, size.height * .2f, size.width * .52f, size.height * .28f)
            lineTo(size.width * .58f, size.height * .36f)
            lineTo(size.width * .82f, size.height * .36f)
            quadraticTo(size.width * .9f, size.height * .36f, size.width * .9f, size.height * .46f)
            lineTo(size.width * .9f, size.height * .75f)
            quadraticTo(size.width * .9f, size.height * .84f, size.width * .8f, size.height * .84f)
            lineTo(size.width * .2f, size.height * .84f)
            quadraticTo(size.width * .1f, size.height * .84f, size.width * .1f, size.height * .74f)
            close()
        }
        if (filled) {
            drawPath(path, color)
        } else {
            drawPath(
                path,
                color,
                style = Stroke(
                    width = size.minDimension * .105f,
                    cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
private fun TrashedNote(note: Note, actions: NotesActions, modifier: Modifier) {
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                note.markdown.ifBlank { "This note is empty." },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(18.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KgsTooltip("Restore") {
                KgsExpressiveSurface(
                    onClick = actions.onRestore,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    restingCorners = KgsCornerRadii(20.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("trash-restore-action"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Restore", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            KgsTooltip("Delete Permanently") {
                KgsExpressiveSurface(
                    onClick = { deleteConfirmationOpen = true },
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    restingCorners = KgsCornerRadii(20.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("trash-delete-action"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = DestructiveRed)
                        Text(
                            "Delete permanently",
                            style = MaterialTheme.typography.labelLarge,
                            color = DestructiveRed,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
    NoteDeleteConfirmationDialog(
        expanded = deleteConfirmationOpen,
        permanently = true,
        titles = listOf(note.title),
        onConfirm = {
            deleteConfirmationOpen = false
            actions.onDeletePermanently()
        },
        onDismiss = { deleteConfirmationOpen = false },
    )

}

@Composable
private fun NoSelection(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Select a note", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your editor will open here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LibrarySort.label: String
    get() = when (this) {
        LibrarySort.LAST_EDITED -> "Last edited"
        LibrarySort.LAST_CREATED -> "Last created"
        LibrarySort.ALPHABETICAL -> "Alphabetical"
    }

private val AttachmentMetadataPreference.label: String
    get() = when (this) {
        AttachmentMetadataPreference.ASK_EVERY_TIME -> "Ask every time"
        AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> "Remove private metadata"
        AttachmentMetadataPreference.KEEP_ORIGINAL -> "Keep original"
    }

private val pendingSyncStates = setOf(
    NoteSyncState.SAVED_LOCALLY,
    NoteSyncState.SYNCING,
    NoteSyncState.NEEDS_ATTENTION,
)

private val FavoriteRed = Color(0xFFD13B4B)

/** Favorite gestures share the Favorites filter's red palette in either app theme. */
@Composable
internal fun favoriteContainerColor(): Color = if (LocalKgsDarkTheme.current) {
    lerp(MaterialTheme.colorScheme.surface, Color(0xFFFF8A9A), .18f)
} else Color(0xFFFFE9E9)
private val DestructiveRed = Color(0xFFBA1A1A)

private fun Modifier.editorTitleBackOverlap(overlap: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = overlap.roundToPx()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else androidx.compose.ui.unit.Constraints.Infinity,
        minHeight = constraints.minHeight, maxHeight = constraints.maxHeight,
    ))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(-extra, 0) }
}
