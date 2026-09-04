package com.kgs.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.KgsCornerRadii
import com.kgs.notes.design.KgsDropdownMenuItem
import com.kgs.notes.design.KgsExpressiveSurface
import com.kgs.notes.design.KgsTooltip
import com.kgs.notes.design.defaultEffectsSpec
import com.kgs.notes.design.defaultSpatialSpec
import com.kgs.notes.design.fastEffectsSpec
import com.kgs.notes.design.fastSpatialSpec
import com.kgs.notes.design.rememberKgsHapticClick
import com.kgs.notes.design.collectIsVisuallyPressedAsState
import com.kgs.notes.editor.EditorMode
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
    val onToggleSourceVisibility: (SourceId) -> Unit = {},
    val onDestinationChange: (LibraryDestination) -> Unit = {},
    val onEditorModeChange: (EditorMode) -> Unit = {},
    val onAttachmentMetadataPreferenceChange: (AttachmentMetadataPreference) -> Unit = {},
    val onAddImage: () -> Unit = {},
    val openManagedAttachment: (String) -> InputStream? = { null },
    val onMessageDismissed: () -> Unit = {},
)

@Composable
fun KgsNotesApp(viewModel: NotesViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var imageImportDialogOpen by rememberSaveable { mutableStateOf(false) }
    var removePrivateMetadata by rememberSaveable { mutableStateOf(true) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importImage(it, removePrivateMetadata) }
    }
    KgsNotesScreen(
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
            onToggleSourceVisibility = viewModel::toggleSourceVisibility,
            onDestinationChange = viewModel::openDestination,
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
        ),
    )
    LaunchedEffect(state.sourceConnection.browserUrl) {
        val browserUrl = state.sourceConnection.browserUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
        }
        viewModel.browserLaunchHandled()
    }
    if (imageImportDialogOpen) {
        ImageImportDialog(
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

private enum class NextcloudSetupMode { BROWSER, APP_PASSWORD }

@Composable
private fun NextcloudConnectionDialog(
    state: SourceConnectionUiState,
    onBrowserConnect: (String, String) -> Unit,
    onAppPasswordConnect: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var sourceName by rememberSaveable { mutableStateOf("Nextcloud") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var loginName by rememberSaveable { mutableStateOf("") }
    var appPassword by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(NextcloudSetupMode.BROWSER) }
    val busy = state.phase in setOf(
        SourceConnectionPhase.STARTING,
        SourceConnectionPhase.WAITING_FOR_BROWSER,
        SourceConnectionPhase.VERIFYING,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nextcloud-connection-dialog"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                Text("Add Nextcloud Source", style = MaterialTheme.typography.titleLarge)
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
                    enabled = !busy,
                )
                if (mode == NextcloudSetupMode.APP_PASSWORD) {
                    KgsConnectionField(
                        value = loginName,
                        onValueChange = { loginName = it },
                        label = "Username",
                        enabled = !busy,
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
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
            disabledContainerColor = MaterialTheme.colorScheme.background,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
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

@Composable
private fun ImageImportDialog(
    removePrivateMetadata: Boolean,
    onRemovePrivateMetadataChange: (Boolean) -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
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
                val chooseImage = rememberKgsHapticClick(onChoose)
                Button(
                    onClick = chooseImage,
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
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var newNoteMorphActive by remember { mutableStateOf(false) }
    LaunchedEffect(state.userMessage) {
        if (state.userMessage != null) {
            delay(4_000)
            actions.onMessageDismissed()
        }
    }
    BackHandler(enabled = state.selectedNote != null, onBack = actions.onCloseEditor)
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    BackHandler(
        enabled = state.selectedNote == null && !drawerOpen && state.destination != LibraryDestination.NOTES,
    ) { actions.onDestinationChange(LibraryDestination.NOTES) }

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
                    onOpenDrawer = { drawerOpen = true },
                    selectedId = state.selectedNote?.id,
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
                AnimatedContent(
                    targetState = state.selectedNote,
                    contentKey = { it?.id },
                    transitionSpec = {
                        if (newNoteMorphActive) {
                            fadeIn(motion.defaultEffectsSpec()) togetherWith
                                fadeOut(motion.fastEffectsSpec())
                        } else if (targetState != null) {
                            slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 } +
                                fadeIn(motion.defaultEffectsSpec()) togetherWith
                                slideOutHorizontally(motion.fastSpatialSpec()) { -it / 5 } +
                                fadeOut(motion.fastEffectsSpec())
                        } else {
                            slideInHorizontally(motion.defaultSpatialSpec()) { -it / 5 } +
                                fadeIn(motion.defaultEffectsSpec()) togetherWith
                                slideOutHorizontally(motion.fastSpatialSpec()) { it / 4 } +
                                fadeOut(motion.fastEffectsSpec())
                        }
                    },
                    label = "library editor navigation",
                    modifier = Modifier.fillMaxSize(),
                ) { note ->
                    val visibilityScope = this
                    if (note == null) {
                        val libraryActions = actions.copy(
                            onCreate = {
                                newNoteMorphActive = true
                                actions.onCreate()
                            },
                            onSelect = { id ->
                                newNoteMorphActive = false
                                actions.onSelect(id)
                            },
                        )
                        LibraryPane(
                            state = state,
                            actions = libraryActions,
                            onOpenDrawer = { drawerOpen = true },
                            selectedId = null,
                            newNoteButtonModifier = Modifier.newNoteMorphBounds(
                                sharedScope = sharedScope,
                                visibilityScope = visibilityScope,
                                ownCorner = 18.dp,
                                counterpartCorner = 0.dp,
                                enabled = newNoteMorphActive,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        EditorPane(
                            state = state,
                            note = note,
                            actions = actions,
                            showBack = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .newNoteMorphBounds(
                                    sharedScope = sharedScope,
                                    visibilityScope = visibilityScope,
                                    ownCorner = 0.dp,
                                    counterpartCorner = 18.dp,
                                    enabled = newNoteMorphActive,
                                ),
                        )
                    }
                }
            }
        }

        NotesDrawer(
            visible = drawerOpen,
            state = state,
            onDismiss = { drawerOpen = false },
            onToggleSource = actions.onToggleSourceVisibility,
            onDestination = { destination ->
                actions.onDestinationChange(destination)
                drawerOpen = false
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
    if (state.sourceConnection.visible) {
        NextcloudConnectionDialog(
            state = state.sourceConnection,
            onBrowserConnect = actions.onBrowserSourceConnect,
            onAppPasswordConnect = actions.onAppPasswordSourceConnect,
            onDismiss = actions.onDismissSourceConnection,
        )
    }
}

@Composable
private fun LibraryPane(
    state: NotesUiState,
    actions: NotesActions,
    onOpenDrawer: () -> Unit = {},
    selectedId: NoteId?,
    newNoteButtonModifier: Modifier = Modifier,
    modifier: Modifier,
) {
    val motion = LocalKgsMotion.current
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = modifier.testTag("library-pane"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state.destination == LibraryDestination.NOTES) {
                KgsTooltip("New Note") {
                    Box(Modifier.padding(bottom = navigationBottom)) {
                        KgsExpressiveSurface(
                            onClick = actions.onCreate,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            restingCorners = KgsCornerRadii(18.dp),
                            showIndication = false,
                            modifier = newNoteButtonModifier.size(56.dp),
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
            LibraryHeader(
                destination = state.destination,
                onOpenDrawer = onOpenDrawer,
                onBackToNotes = { actions.onDestinationChange(LibraryDestination.NOTES) },
            )
            AnimatedContent(
                targetState = state.destination,
                transitionSpec = {
                    if (targetState == LibraryDestination.NOTES) {
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
            ) { destination ->
                if (destination == LibraryDestination.SETTINGS) {
                    SettingsPane(
                        state = state,
                        actions = actions,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 10.dp),
                    )
                } else {
                    LibraryCollection(
                        state = state,
                        actions = actions,
                        destination = destination,
                        selectedId = selectedId,
                        navigationBottom = navigationBottom,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun Modifier.newNoteMorphBounds(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    ownCorner: androidx.compose.ui.unit.Dp,
    counterpartCorner: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val motion = LocalKgsMotion.current
    val animatedCorner by visibilityScope.transition.animateDp(
        transitionSpec = { motion.defaultSpatialSpec() },
        label = "new note container corner",
    ) { visibility ->
        if (visibility == EnterExitState.Visible) ownCorner else counterpartCorner
    }
    return with(sharedScope) {
        this@newNoteMorphBounds.sharedBounds(
            sharedContentState = rememberSharedContentState("new-note-container"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec() },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            placeholderSize = SharedTransitionScope.PlaceholderSize.AnimatedSize,
            // Spatial springs intentionally overshoot. Corner radii cannot, so
            // constrain only the physical boundary while preserving the bounce.
            clipInOverlayDuringTransition = OverlayClip(
                RoundedCornerShape(animatedCorner.coerceAtLeast(0.dp)),
            ),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            zIndexInOverlay = 2f,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCollection(
    state: NotesUiState,
    actions: NotesActions,
    destination: LibraryDestination,
    selectedId: NoteId?,
    navigationBottom: androidx.compose.ui.unit.Dp,
) {
    val motion = LocalKgsMotion.current
    val focusManager = LocalFocusManager.current
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf<Rect?>(null) }
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
            .pointerInput(searchFocused, searchBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                    val bounds = searchBounds
                    if (up != null && searchFocused && bounds != null && !bounds.contains(down.position)) {
                        focusManager.clearFocus()
                    }
                }
            },
    ) {
        Spacer(Modifier.height(10.dp))
        if (destination == LibraryDestination.NOTES) {
            KgsSearchField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                placeholder = "Search notes",
                interactionSource = searchInteractions,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { searchBounds = it.boundsInParent() }
                    .testTag("notes-search"),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val filterInteractions = remember {
                    LibraryFilter.entries.map { MutableInteractionSource() }
                }
                val filterPressed = filterInteractions.map { it.collectIsVisuallyPressedAsState().value }
                val pressedFilter = filterPressed.indexOfFirst { it }
                val pressedNeighborCount = listOf(pressedFilter - 1, pressedFilter + 1)
                    .count { it in LibraryFilter.entries.indices }
                LibraryFilter.entries.forEachIndexed { index, filter ->
                    val targetWidth = when {
                        pressedFilter < 0 -> 44.dp
                        index == pressedFilter -> 50.6.dp
                        kotlin.math.abs(index - pressedFilter) == 1 -> 44.dp - 6.6.dp / pressedNeighborCount
                        else -> 44.dp
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
                val directionPressed by directionInteractions.collectIsVisuallyPressedAsState()
                val sortPressed by sortInteractions.collectIsVisuallyPressedAsState()
                val directionWidth by animateDpAsState(
                    targetValue = when {
                        directionPressed -> 50.6.dp
                        sortPressed -> 37.4.dp
                        else -> 44.dp
                    },
                    animationSpec = motion.fastSpatialSpec(),
                    label = "sort direction group width",
                )
                val sortWidth by animateDpAsState(
                    targetValue = when {
                        sortPressed -> 50.6.dp
                        directionPressed -> 37.4.dp
                        else -> 44.dp
                    },
                    animationSpec = motion.fastSpatialSpec(),
                    label = "sort group width",
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
                            SortGlyph(
                                color = sortContent,
                                modifier = Modifier.semantics { contentDescription = "Sort notes" },
                            )
                        }
                    }
                    val sortMenuShape = RoundedCornerShape(20.dp)
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                        offset = DpOffset(0.dp, 8.dp),
                        shape = sortMenuShape,
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 3.dp,
                        modifier = Modifier.kgsMenuShadow(sortMenuShape),
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
            }
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        if (state.notes.isEmpty()) {
            EmptyLibrary(
                filter = state.filter,
                destination = destination,
                query = state.query,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("empty-library"),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.notes, key = { it.id.value }) { note ->
                    NoteCard(
                        note = note,
                        selected = note.id == selectedId,
                        onClick = { actions.onSelect(note.id) },
                        modifier = Modifier.animateItem(
                            placementSpec = motion.defaultSpatialSpec(),
                        ),
                    )
                }
                item { Spacer(Modifier.height(92.dp + navigationBottom)) }
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
            onClick = if (destination == LibraryDestination.NOTES) onOpenDrawer else onBackToNotes,
        ) {
            if (destination == LibraryDestination.NOTES) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Notes")
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("K", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
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

private fun Modifier.kgsMenuShadow(shape: RoundedCornerShape): Modifier = shadow(
    elevation = 18.dp,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = .065f),
    spotColor = Color.Black.copy(alpha = .1f),
)

@Composable
private fun ExpressiveIconButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
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
            modifier = Modifier
                .size(40.dp),
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
    val iconColor by animateColorAsState(
        targetValue = if (filter == LibraryFilter.FAVORITES && selected) {
            FavoriteRed
        } else if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "$description icon color",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
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
                        LibraryFilter.ALL -> AllNotesGlyph(iconColor, filled = active)
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
            if (filter == LibraryFilter.LOCAL && pendingSyncCount > 0) {
                Surface(
                color = Color(0xFFF28C28),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            pendingSyncCount.coerceAtMost(99).toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
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
private fun SortGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        listOf(.29f to .78f, .51f to .6f, .73f to .4f).forEach { (y, width) ->
            drawLine(
                color,
                Offset(size.width * .16f, size.height * y),
                Offset(size.width * width, size.height * y),
                stroke,
                StrokeCap.Round,
            )
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
private fun AllNotesGlyph(color: Color, filled: Boolean = false) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .08f
        listOf(.28f, .5f, .72f).forEach { y ->
            drawLine(
                color,
                Offset(size.width * (if (filled) .16f else .2f), size.height * y),
                Offset(size.width * (if (filled) .84f else .8f), size.height * y),
                if (filled) stroke * 1.5f else stroke,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun LocalNotesGlyph(color: Color, filled: Boolean = false) {
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
private fun CloudGlyph(color: Color, filled: Boolean = false) {
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
private fun PrivacyGlyph(color: Color) {
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
private fun QuestionGlyph(color: Color) {
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
private fun NotesDrawer(
    visible: Boolean,
    state: NotesUiState,
    onDismiss: () -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onDestination: (LibraryDestination) -> Unit,
) {
    val motion = LocalKgsMotion.current
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) motion.defaultSpatialSpec() else motion.fastSpatialSpec(),
        label = "drawer slide",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(15.dp),
                                modifier = Modifier.size(46.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("K", fontWeight = FontWeight.Black, fontSize = 21.sp)
                                }
                            }
                            Column {
                                Text("KGS Notes", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Portable by design",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Sources",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.sources.forEach { source ->
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
                        Spacer(Modifier.weight(1f))
                        DrawerRow(
                            label = "Trash",
                            selected = state.destination == LibraryDestination.TRASH,
                            onClick = { onDestination(LibraryDestination.TRASH) },
                            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        )
                        Spacer(Modifier.height(4.dp))
                        DrawerRow(
                            label = "Settings",
                            selected = state.destination == LibraryDestination.SETTINGS,
                            onClick = { onDestination(LibraryDestination.SETTINGS) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        )
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    KgsTooltip(label) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            restingCorners = KgsCornerRadii(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("modal-choice-$label"),
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
                        .testTag("modal-choice-label-$label"),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(
    state: NotesUiState,
    actions: NotesActions,
    modifier: Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        item {
            Text(
                "Sources",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        items(state.sources, key = { it.id.value }) { source ->
            SettingsCard(
                title = source.name,
                value = if (source.id == LocalSourceId) "On this device" else "Nextcloud Notes",
            )
        }
        item {
            AddSourceSettingsCard(onClick = actions.onAddSource)
        }
        item {
            DefaultSourceSettingsCard(
                sources = state.sources,
                selectedId = state.defaultSourceId,
                onSelect = actions.onDefaultSourceChange,
            )
        }
        item {
            NoteFormatSettingsCard(
                preference = state.noteFormatPreference,
                onPreferenceChange = actions.onNoteFormatPreferenceChange,
            )
        }
        item { SettingsCard("Editor", "Rich Mode with Source Mode available") }
        item {
            AttachmentMetadataSettingsCard(
                preference = state.attachmentMetadataPreference,
                onPreferenceChange = actions.onAttachmentMetadataPreferenceChange,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NoteFormatSettingsCard(
    preference: NoteFormatPreference,
    onPreferenceChange: (NoteFormatPreference) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        KgsTooltip("Note format") {
            KgsExpressiveSurface(
                onClick = { menuOpen = true },
                color = MaterialTheme.colorScheme.surface,
                restingCorners = KgsCornerRadii(14.dp),
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Note format: ${preference.label}" },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Note format", style = MaterialTheme.typography.titleMedium)
                    Text(
                        preference.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val shape = RoundedCornerShape(20.dp)
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(0.dp, 8.dp),
            shape = shape,
            containerColor = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier.kgsMenuShadow(shape),
        ) {
            NoteFormatPreference.entries.forEach { option ->
                KgsDropdownMenuItem(
                    text = option.label,
                    selected = option == preference,
                    icon = {
                        NoteFormatGlyph(option, MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = {
                        onPreferenceChange(option)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NoteFormatGlyph(format: NoteFormatPreference, color: Color) {
    if (format == NoteFormatPreference.FOLLOW_SOURCE) {
        CloudGlyph(color)
        return
    }
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .09f
        if (format == NoteFormatPreference.MARKDOWN) {
            val yTop = size.height * .31f
            val yBottom = size.height * .70f
            val points = listOf(
                Offset(size.width * .18f, yBottom),
                Offset(size.width * .18f, yTop),
                Offset(size.width * .40f, yBottom * .87f),
                Offset(size.width * .62f, yTop),
                Offset(size.width * .62f, yBottom),
            )
            points.zipWithNext().forEach { (start, end) ->
                drawLine(color, start, end, stroke, StrokeCap.Round)
            }
            drawLine(
                color,
                Offset(size.width * .76f, yTop),
                Offset(size.width * .76f, yBottom),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(size.width * .67f, yBottom * .86f),
                Offset(size.width * .76f, yBottom),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(size.width * .85f, yBottom * .86f),
                Offset(size.width * .76f, yBottom),
                stroke,
                StrokeCap.Round,
            )
        } else {
            listOf(.30f to .76f, .50f to .62f, .70f to .46f).forEach { (y, endX) ->
                drawLine(
                    color,
                    Offset(size.width * .18f, size.height * y),
                    Offset(size.width * endX, size.height * y),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun AddSourceSettingsCard(onClick: () -> Unit) {
    KgsTooltip("Add Source") {
        KgsExpressiveSurface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            restingCorners = KgsCornerRadii(14.dp),
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Add Nextcloud Source" },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Column {
                    Text("Add Source", style = MaterialTheme.typography.titleMedium)
                    Text("Connect Nextcloud", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DefaultSourceSettingsCard(
    sources: List<SourceChoice>,
    selectedId: SourceId,
    onSelect: (SourceId) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = sources.firstOrNull { it.id == selectedId } ?: sources.first()
    Box {
        KgsTooltip("Default Source") {
            KgsExpressiveSurface(
                onClick = { menuOpen = true },
                color = MaterialTheme.colorScheme.surface,
                restingCorners = KgsCornerRadii(14.dp),
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Default Source", style = MaterialTheme.typography.titleMedium)
                    Text(
                        selected.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val shape = RoundedCornerShape(20.dp)
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(0.dp, 8.dp),
            shape = shape,
            containerColor = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier.kgsMenuShadow(shape),
        ) {
            sources.forEach { source ->
                KgsDropdownMenuItem(
                    text = source.name,
                    selected = source.id == selected.id,
                    icon = {
                        if (source.id == LocalSourceId) {
                            LocalNotesGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CloudGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        onSelect(source.id)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentMetadataSettingsCard(
    preference: AttachmentMetadataPreference,
    onPreferenceChange: (AttachmentMetadataPreference) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        KgsTooltip("Attachment metadata") {
            KgsExpressiveSurface(
                onClick = { menuOpen = true },
                color = MaterialTheme.colorScheme.surface,
                restingCorners = KgsCornerRadii(14.dp),
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Attachment metadata: ${preference.label}"
                    },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Attachment metadata", style = MaterialTheme.typography.titleMedium)
                    Text(
                        preference.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val menuShape = RoundedCornerShape(20.dp)
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(0.dp, 8.dp),
            shape = menuShape,
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.kgsMenuShadow(menuShape),
        ) {
            AttachmentMetadataPreference.entries.forEach { option ->
                KgsDropdownMenuItem(
                    text = option.label,
                    selected = option == preference,
                    icon = {
                        when (option) {
                            AttachmentMetadataPreference.ASK_EVERY_TIME -> QuestionGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            AttachmentMetadataPreference.REMOVE_PRIVATE_METADATA -> PrivacyGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                            AttachmentMetadataPreference.KEEP_ORIGINAL -> ImageGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        onPreferenceChange(option)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var previewOverflows by remember(note.id, note.snippet) { mutableStateOf(false) }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    KgsTooltip("Open Note") {
        KgsExpressiveSurface(
            onClick = onClick,
            color = container,
            restingCorners = KgsCornerRadii(14.dp),
            showIndication = false,
            modifier = modifier
                .fillMaxWidth()
                .testTag("note-card-${note.id.value}"),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(17.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (note.sourceId != LocalSourceId && note.syncState in pendingSyncStates) {
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            color = Color(0xFFF28C28),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(10.dp)
                                .semantics { contentDescription = "Awaiting sync" },
                        ) {}
                    }
                }
                if (note.snippet.isNotBlank()) {
                    Text(
                        text = note.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { previewOverflows = it.hasVisualOverflow },
                        modifier = Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                if (previewOverflows) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0f to Color.White,
                                                .62f to Color.White,
                                                1f to Color.Transparent,
                                            ),
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (note.favorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = FavoriteRed,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (note.category.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                note.category,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
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
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    else -> "A quiet place for your thoughts"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    query.isNotBlank() -> "Try another search term."
                    destination == LibraryDestination.TRASH -> "Deleted notes stay recoverable here."
                    filter == LibraryFilter.SERVER -> "Synced notes from connected Sources appear here."
                    else -> "Create a note and it will be stored as portable Markdown."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorPane(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .testTag("note-editor"),
    ) {
        EditorHeader(state, note, actions, showBack)
        if (note.state == NoteState.TRASHED) {
            TrashedNote(note, actions, Modifier.fillMaxSize())
        } else {
            KgsMarkdownEditor(
                markdown = note.markdown,
                requestedMode = state.editorMode,
                onMarkdownChange = actions.onContentChange,
                onModeChange = actions.onEditorModeChange,
                onAddImage = actions.onAddImage,
                openManagedAttachment = actions.openManagedAttachment,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EditorHeader(
    state: NotesUiState,
    note: Note,
    actions: NotesActions,
    showBack: Boolean,
) {
    var categoryDialogOpen by remember { mutableStateOf(false) }
    var sourceDialogOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.padding(start = 6.dp, top = statusTop + 2.dp, end = 6.dp, bottom = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (showBack) {
                ExpressiveIconButton(label = "Back", onClick = actions.onCloseEditor) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Notes",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            BasicTextField(
                value = note.title,
                onValueChange = actions.onRename,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val fadeWidth = 30.dp.toPx()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.White,
                                    ((size.width - fadeWidth) / size.width).coerceIn(0f, 1f) to Color.White,
                                    1f to Color.Transparent,
                                ),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            SaveStatusIndicator(
                status = when {
                    state.saving -> EditorSaveStatus.SAVING
                    note.syncState == NoteSyncState.SYNCING -> EditorSaveStatus.SYNCING
                    note.syncState == NoteSyncState.SYNCED -> EditorSaveStatus.SYNCED
                    else -> EditorSaveStatus.SAVED_LOCALLY
                },
                onClick = { sourceDialogOpen = true },
            )
            if (note.state != NoteState.TRASHED) {
                KgsTooltip("Category") {
                    KgsExpressiveSurface(
                        onClick = { categoryDialogOpen = true },
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
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Box {
                    ExpressiveIconButton(
                        label = "More",
                        onClick = { actionsMenuOpen = true },
                        selected = actionsMenuOpen,
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More note actions",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val actionsMenuShape = RoundedCornerShape(20.dp)
                    DropdownMenu(
                        expanded = actionsMenuOpen,
                        onDismissRequest = { actionsMenuOpen = false },
                        offset = DpOffset(0.dp, 8.dp),
                        shape = actionsMenuShape,
                        containerColor = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 3.dp,
                        modifier = Modifier.kgsMenuShadow(actionsMenuShape),
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
                                    actions.onMoveToTrash()
                                    actionsMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (categoryDialogOpen) {
        CategoryDialog(
            current = note.category,
            categories = state.categories,
            onSelect = {
                actions.onCategoryChange(it)
                categoryDialogOpen = false
            },
            onDismiss = { categoryDialogOpen = false },
        )
    }
    if (sourceDialogOpen) {
        SourceDialog(
            current = note.sourceId,
            sources = state.sources,
            onSelect = {
                actions.onSourceChange(it)
                sourceDialogOpen = false
            },
            onDismiss = { sourceDialogOpen = false },
        )
    }
}

@Composable
private fun CategoryDialog(
    current: String,
    categories: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidate = query.trim()
    val createCategory = rememberKgsHapticClick { onSelect(candidate) }
    Dialog(onDismissRequest = onDismiss) {
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
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    item {
                        ModalChoiceRow(
                            label = "No Category",
                            selected = current.isBlank(),
                            onClick = { onSelect("") },
                            icon = {
                                CategoryIcon(false, MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                    items(
                        items = categories.filter { it.contains(candidate, ignoreCase = true) },
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
                                        MaterialTheme.colorScheme.primary
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
                        enabled = candidate.isNotEmpty() && categories.none { it.equals(candidate, ignoreCase = true) },
                        onClick = createCategory,
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

@Composable
private fun SourceDialog(
    current: SourceId,
    sources: List<SourceChoice>,
    onSelect: (SourceId) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
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
                    "The Working Copy stays available offline.",
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
    KgsTooltip(label) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            restingCorners = KgsCornerRadii(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KgsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    containerColor: Color = Color.White,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(interactionSource, haptics) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            }
        }
    }
    val motion = LocalKgsMotion.current
    val corner by animateDpAsState(
        targetValue = if (focused) 28.dp else 12.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "$placeholder search shape",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "$placeholder search border",
    )
    val shape = RoundedCornerShape(corner)
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        interactionSource = interactionSource,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.border(2.dp, borderColor, shape),
    )
}

private enum class EditorSaveStatus(val description: String) {
    SAVING("Saving"),
    SAVED_LOCALLY("Saved locally"),
    SYNCING("Saved locally, syncing"),
    SYNCED("Synced"),
}

@Composable
private fun SaveStatusIndicator(status: EditorSaveStatus, onClick: () -> Unit) {
    val motion = LocalKgsMotion.current
    val blue = Color(0xFF2878D0)
    val orange = Color(0xFFF28C28)
    val green = Color(0xFF2E9B62)
    val color by animateColorAsState(
        targetValue = when (status) {
            EditorSaveStatus.SAVING -> orange
            EditorSaveStatus.SAVED_LOCALLY, EditorSaveStatus.SYNCING -> blue
            EditorSaveStatus.SYNCED -> green
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "save status color",
    )
    val glyphProgress by animateFloatAsState(
        targetValue = if (status == EditorSaveStatus.SAVING) 0f else 1f,
        animationSpec = motion.defaultSpatialSpec(),
        label = "save status glyph",
    )
    val sweep by animateFloatAsState(
        targetValue = if (status == EditorSaveStatus.SAVING) 270f else 360f,
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

    KgsTooltip(status.description) {
        KgsExpressiveSurface(
            onClick = onClick,
            restingCorners = KgsCornerRadii(19.dp),
            modifier = Modifier
                .size(38.dp)
                .semantics { contentDescription = status.description },
        ) {
            Canvas(Modifier.size(24.dp)) {
                val stroke = size.minDimension * .09f
                val inset = stroke / 2
                rotate(if (status == EditorSaveStatus.SAVING) rotation else 0f) {
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
            EditorSaveStatus.SAVING -> Unit
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
            EditorSaveStatus.SYNCING -> {
                drawLine(orange, Offset(size.width * .5f, size.height * .72f), Offset(size.width * .5f, size.height * .3f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .32f, size.height * .48f), stroke, StrokeCap.Round)
                drawLine(orange, Offset(size.width * .5f, size.height * .3f), Offset(size.width * .68f, size.height * .48f), stroke, StrokeCap.Round)
            }
            EditorSaveStatus.SYNCED -> {
                val glyph = green.copy(alpha = glyphProgress.coerceIn(0f, 1f))
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .27f, size.height * .64f)
                    cubicTo(size.width * .13f, size.height * .64f, size.width * .13f, size.height * .44f, size.width * .3f, size.height * .43f)
                    cubicTo(size.width * .36f, size.height * .22f, size.width * .66f, size.height * .24f, size.width * .7f, size.height * .46f)
                    cubicTo(size.width * .86f, size.height * .47f, size.width * .86f, size.height * .65f, size.width * .72f, size.height * .65f)
                }
                drawPath(path, glyph, style = Stroke(stroke * .78f, cap = StrokeCap.Round))
            }
                }
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
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.padding(24.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                note.markdown.ifBlank { "This note is empty." },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(18.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KgsTooltip("Restore") {
                KgsExpressiveSurface(
                    onClick = actions.onRestore,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    restingCorners = KgsCornerRadii(20.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Restore", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            KgsTooltip("Delete Permanently") {
                KgsExpressiveSurface(
                    onClick = actions.onDeletePermanently,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    restingCorners = KgsCornerRadii(20.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = DestructiveRed)
                        Text(
                            "Delete permanently",
                            style = MaterialTheme.typography.labelLarge,
                            color = DestructiveRed,
                        )
                    }
                }
            }
        }
    }
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
private val DestructiveRed = Color(0xFFBA1A1A)
