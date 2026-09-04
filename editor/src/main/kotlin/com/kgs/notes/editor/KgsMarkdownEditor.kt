package com.kgs.notes.editor

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import com.kgs.notes.design.KgsTooltip
import com.kgs.notes.design.KgsCornerRadii
import com.kgs.notes.design.KgsDropdownMenuItem
import com.kgs.notes.design.KgsExpressiveSurface
import com.kgs.notes.design.LocalKgsMotion
import com.kgs.notes.design.defaultEffectsSpec
import com.kgs.notes.design.defaultSpatialSpec
import com.kgs.notes.design.fastEffectsSpec
import com.kgs.notes.design.fastSpatialSpec
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.InputStream

enum class EditorMode {
    RICH,
    SOURCE,
}

@Composable
fun KgsMarkdownEditor(
    markdown: String,
    requestedMode: EditorMode,
    onMarkdownChange: (String) -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onAddImage: () -> Unit = {},
    openManagedAttachment: (String) -> InputStream? = { null },
    modifier: Modifier = Modifier,
    editor: MarkdownEditor = remember { DefaultMarkdownEditor() },
) {
    val safety = remember(markdown, editor) { editor.safetyFor(markdown) }
    val richModeBlocker = when {
        !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ->
            "This Android WebView requires Source Mode"
        safety is EditorSafety.SourceMode -> safety.reason
        else -> null
    }
    val effectiveMode = when {
        requestedMode == EditorMode.SOURCE -> EditorMode.SOURCE
        richModeBlocker != null -> EditorMode.SOURCE
        else -> EditorMode.RICH
    }

    val commands = remember { EditorCommandDispatcher() }
    var activeCommands by remember { mutableStateOf(emptySet<MarkdownCommand>()) }
    var richEditorReady by remember { mutableStateOf(false) }

    LaunchedEffect(effectiveMode) {
        if (effectiveMode == EditorMode.SOURCE) activeCommands = emptySet()
        richEditorReady = false
    }

    Column(
        modifier
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (requestedMode == EditorMode.RICH && richModeBlocker != null) {
            SourceFallbackNotice(richModeBlocker)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds(),
        ) {
            when (effectiveMode) {
                EditorMode.RICH -> RichMarkdownEditor(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    onActiveCommandsChange = { activeCommands = it },
                    onReady = { richEditorReady = true },
                    commands = commands,
                    openManagedAttachment = openManagedAttachment,
                    modifier = Modifier.fillMaxSize(),
                )

                EditorMode.SOURCE -> SourceMarkdownEditor(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    commands = commands,
                    editor = editor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (richEditorReady) {
                Spacer(
                    Modifier
                        .size(1.dp)
                        .testTag("rich-editor-ready"),
                )
            }
        }
        EditorToolbar(
            mode = effectiveMode,
            activeCommands = activeCommands,
            onModeChange = onModeChange,
            onCommand = commands::dispatch,
            onAddImage = onAddImage,
        )
    }
}

@Composable
private fun SourceFallbackNotice(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("Source Mode", style = MaterialTheme.typography.labelLarge)
            Text(reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SourceMarkdownEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    commands: EditorCommandDispatcher,
    editor: MarkdownEditor,
    modifier: Modifier,
) {
    val field = rememberTextFieldState(markdown)
    val latestOnMarkdownChange by rememberUpdatedState(onMarkdownChange)
    val commandOwner = remember { Any() }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }
    var lastObserved by remember {
        mutableStateOf(TextFieldValue(markdown, selection = field.selection))
    }
    var ignoredHistoryText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(markdown) {
        if (markdown != field.text.toString()) {
            ignoredHistoryText = markdown
            field.edit {
                val preservedSelection = TextRange(
                    selection.start.coerceAtMost(markdown.length),
                    selection.end.coerceAtMost(markdown.length),
                )
                replace(0, length, markdown)
                selection = preservedSelection
            }
        }
    }
    LaunchedEffect(field) {
        snapshotFlow { TextFieldValue(field.text.toString(), selection = field.selection) }
            .collect { updated ->
                if (updated.text != lastObserved.text) {
                    if (updated.text == ignoredHistoryText) {
                        ignoredHistoryText = null
                    } else {
                        undo.addLast(lastObserved)
                        redo.clear()
                    }
                    latestOnMarkdownChange(updated.text)
                }
                lastObserved = updated
            }
    }
    SideEffect {
        commands.connect(commandOwner) { command ->
            when (command) {
                MarkdownCommand.UNDO -> undo.removeLastOrNull()?.let { previous ->
                    redo.addLast(lastObserved)
                    ignoredHistoryText = previous.text
                    field.edit {
                        replace(0, length, previous.text)
                        selection = previous.selection
                    }
                }
                MarkdownCommand.REDO -> redo.removeLastOrNull()?.let { next ->
                    undo.addLast(lastObserved)
                    ignoredHistoryText = next.text
                    field.edit {
                        replace(0, length, next.text)
                        selection = next.selection
                    }
                }
                else -> {
                    val edit = editor.applyCommand(
                        markdown = field.text.toString(),
                        selection = MarkdownSelection(field.selection.start, field.selection.end),
                        command = command,
                    )
                    if (edit.markdown != field.text.toString()) {
                        field.edit {
                            replace(0, length, edit.markdown)
                            selection = TextRange(edit.selection.start, edit.selection.end)
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(commands, commandOwner) {
        onDispose { commands.disconnect(commandOwner) }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BasicTextField(
            state = field,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .testTag("source-editor"),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
@Suppress("DEPRECATION") // Explicitly disable legacy file-URL access on older WebView implementations.
@Composable
private fun RichMarkdownEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    onActiveCommandsChange: (Set<MarkdownCommand>) -> Unit,
    onReady: () -> Unit,
    commands: EditorCommandDispatcher,
    openManagedAttachment: (String) -> InputStream?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val darkMode = isSystemInDarkTheme()
    val latestMarkdown by rememberUpdatedState(markdown)
    val latestDarkMode by rememberUpdatedState(darkMode)
    val latestOnMarkdownChange by rememberUpdatedState(onMarkdownChange)
    val latestOnActiveCommandsChange by rememberUpdatedState(onActiveCommandsChange)
    val latestOnReady by rememberUpdatedState(onReady)
    val latestOpenManagedAttachment by rememberUpdatedState(openManagedAttachment)
    var rendererGeneration by remember { mutableIntStateOf(0) }
    var editorMarkdown by remember { mutableStateOf(markdown) }
    var connectedWebView by remember(rendererGeneration) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(markdown, connectedWebView) {
        val webView = connectedWebView ?: return@LaunchedEffect
        if (markdown != editorMarkdown) {
            editorMarkdown = markdown
            webView.loadMarkdown(markdown)
        }
    }
    LaunchedEffect(darkMode, connectedWebView) {
        connectedWebView?.setDarkMode(darkMode)
    }

    key(rendererGeneration) {
        AndroidView(
            factory = {
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    importantForAccessibility = WebView.IMPORTANT_FOR_ACCESSIBILITY_YES
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            request.managedAttachmentRequest()?.let { attachmentRequest ->
                                val content = latestOpenManagedAttachment(attachmentRequest.id)
                                    ?: return WebResourceResponse(null, null, null)
                                return WebResourceResponse(
                                    attachmentRequest.mediaType,
                                    null,
                                    content,
                                )
                            }
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = request.url.host != ASSET_HOST

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: android.net.http.SslError,
                        ) = handler.cancel()

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            view.destroy()
                            rendererGeneration += 1
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) = Unit
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(
                            this,
                            BRIDGE_NAME,
                            setOf(ASSET_ORIGIN),
                        ) { view, message, sourceOrigin, isMainFrame, _ ->
                            if (!isMainFrame || sourceOrigin != Uri.parse(ASSET_ORIGIN)) {
                                return@addWebMessageListener
                            }
                            val payload = runCatching {
                                JSONObject(message.data ?: return@addWebMessageListener)
                            }.getOrNull() ?: return@addWebMessageListener
                            when (payload.optString("type")) {
                                "ready" -> {
                                    view.setDarkMode(latestDarkMode)
                                    editorMarkdown = latestMarkdown
                                    view.loadMarkdown(latestMarkdown)
                                    commands.connect(view) { command ->
                                        view.runCommand(command, latestOnActiveCommandsChange)
                                    }
                                    connectedWebView = view
                                    latestOnReady()
                                }
                                "changed" -> {
                                    val changed = payload.optString("markdown")
                                    editorMarkdown = changed
                                    latestOnMarkdownChange(changed)
                                }
                                "formatting" -> latestOnActiveCommandsChange(
                                    buildSet {
                                        val active = payload.optJSONArray("commands") ?: return@buildSet
                                        for (index in 0 until active.length()) {
                                            markdownCommandForBridgeName(active.optString(index))?.let(::add)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    loadUrl("$ASSET_ORIGIN/assets/kgs-editor/index.html")
                }
            },
            update = {},
            onRelease = { webView ->
                commands.disconnect(webView)
                if (connectedWebView === webView) connectedWebView = null
                webView.destroy()
            },
            modifier = modifier,
        )
    }
}

private data class ManagedAttachmentRequest(
    val id: String,
    val mediaType: String,
)

private fun WebResourceRequest.managedAttachmentRequest(): ManagedAttachmentRequest? {
    if (url.host != ASSET_HOST) return null
    val vaultIndex = url.pathSegments.indexOf(".kgs-notes-attachments")
    if (vaultIndex < 0 || vaultIndex + 2 >= url.pathSegments.size) return null
    val storageName = url.pathSegments[vaultIndex + 2]
    val id = storageName.substringBefore('.')
    if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
    val extension = storageName.substringAfterLast('.', "").lowercase()
    val mediaType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?.takeIf { it.startsWith("image/") }
        ?: return null
    return ManagedAttachmentRequest(id, mediaType)
}

private class EditorCommandDispatcher {
    private var owner: Any? = null
    private var action: ((MarkdownCommand) -> Unit)? = null
    private val pending = ArrayDeque<MarkdownCommand>()

    fun connect(owner: Any, action: (MarkdownCommand) -> Unit) {
        this.owner = owner
        this.action = action
        while (pending.isNotEmpty()) action(pending.removeFirst())
    }

    fun disconnect(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            action = null
        }
    }

    fun dispatch(command: MarkdownCommand) {
        action?.invoke(command) ?: pending.addLast(command)
    }
}

@Composable
private fun EditorToolbar(
    mode: EditorMode,
    activeCommands: Set<MarkdownCommand>,
    onModeChange: (EditorMode) -> Unit,
    onCommand: (MarkdownCommand) -> Unit,
    onAddImage: () -> Unit,
) {
    val motion = LocalKgsMotion.current
    var listMenuOpen by remember { mutableStateOf(false) }
    val listMenuVisibility = remember { MutableTransitionState(false) }
    listMenuVisibility.targetState = listMenuOpen
    val toolsScroll = rememberScrollState()
    val leftFade by animateFloatAsState(
        targetValue = if (toolsScroll.canScrollBackward) 1f else 0f,
        animationSpec = motion.fastEffectsSpec(),
        label = "toolbar left edge fade",
    )
    val rightFade by animateFloatAsState(
        targetValue = if (toolsScroll.canScrollForward) 1f else 0f,
        animationSpec = motion.fastEffectsSpec(),
        label = "toolbar right edge fade",
    )
    Surface(
        color = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("editor-toolbar"),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(46.dp),
            ) {
                Spacer(Modifier.width(9.dp))
                ToolbarButton(
                    description = if (mode == EditorMode.RICH) "Switch to Source Mode" else "Switch to Rich Mode",
                    selected = true,
                    onClick = {
                        onModeChange(if (mode == EditorMode.RICH) EditorMode.SOURCE else EditorMode.RICH)
                    },
                ) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = {
                            (fadeIn(motion.defaultEffectsSpec()) +
                                scaleIn(motion.defaultSpatialSpec(), initialScale = .72f)) togetherWith
                                (fadeOut(motion.fastEffectsSpec()) +
                                    scaleOut(motion.fastSpatialSpec(), targetScale = .72f))
                        },
                        label = "mode icon",
                    ) { current ->
                        ToolbarGlyph(
                            type = if (current == EditorMode.RICH) ToolbarGlyphType.RICH else ToolbarGlyphType.SOURCE,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadeWidth = 15.dp.toPx().coerceAtMost(size.width / 2f)
                            if (leftFade > .001f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 1f - leftFade),
                                            Color.White,
                                        ),
                                        startX = 0f,
                                        endX = fadeWidth,
                                    ),
                                    size = Size(fadeWidth, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            if (rightFade > .001f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White,
                                            Color.White.copy(alpha = 1f - rightFade),
                                        ),
                                        startX = size.width - fadeWidth,
                                        endX = size.width,
                                    ),
                                    topLeft = Offset(size.width - fadeWidth, 0f),
                                    size = Size(fadeWidth, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(toolsScroll),
                    ) {
                        ToolbarIconButton(ToolbarGlyphType.BOLD, "Bold", MarkdownCommand.BOLD in activeCommands) { onCommand(MarkdownCommand.BOLD) }
                        ToolbarIconButton(ToolbarGlyphType.ITALIC, "Italic", MarkdownCommand.ITALIC in activeCommands) { onCommand(MarkdownCommand.ITALIC) }
                        ToolbarIconButton(ToolbarGlyphType.HEADING, "Heading level 2", MarkdownCommand.HEADING_2 in activeCommands) { onCommand(MarkdownCommand.HEADING_2) }
                        Box {
                        val activeList = listOf(
                            MarkdownCommand.BULLET_LIST,
                            MarkdownCommand.TASK_LIST,
                            MarkdownCommand.NUMBERED_LIST,
                        ).firstOrNull { it in activeCommands }
                        ToolbarButton(
                            description = "Lists",
                            selected = listMenuOpen || activeList != null,
                            onClick = { listMenuOpen = true },
                        ) {
                            ToolbarGlyph(
                                type = when (activeList) {
                                    MarkdownCommand.TASK_LIST -> ToolbarGlyphType.CHECKLIST
                                    MarkdownCommand.NUMBERED_LIST -> ToolbarGlyphType.NUMBERED_LIST
                                    else -> ToolbarGlyphType.LIST
                                },
                                color = if (listMenuOpen || activeList != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        val density = LocalDensity.current
                        if (listMenuVisibility.currentState || listMenuVisibility.targetState) {
                            val positionProvider = remember(density) {
                                AboveAnchorPopupPositionProvider(
                                    horizontalOffset = with(density) { 4.dp.roundToPx() },
                                    verticalGap = with(density) { 6.dp.roundToPx() },
                                    windowMargin = with(density) { 8.dp.roundToPx() },
                                )
                            }
                            Popup(
                                popupPositionProvider = positionProvider,
                                onDismissRequest = { listMenuOpen = false },
                                properties = PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true,
                                    clippingEnabled = false,
                                ),
                            ) {
                                ListStyleMenuPopupContent(
                                    visibleState = listMenuVisibility,
                                    activeCommands = activeCommands,
                                    onSelect = { command ->
                                        onCommand(command)
                                        listMenuOpen = false
                                    },
                                )
                            }
                        }
                        }
                        ToolbarIconButton(ToolbarGlyphType.QUOTE, "Quote", MarkdownCommand.BLOCKQUOTE in activeCommands) { onCommand(MarkdownCommand.BLOCKQUOTE) }
                        ToolbarIconButton(ToolbarGlyphType.CODE, "Code block", MarkdownCommand.CODE_BLOCK in activeCommands) { onCommand(MarkdownCommand.CODE_BLOCK) }
                        ToolbarIconButton(ToolbarGlyphType.TABLE, "Insert table", MarkdownCommand.TABLE in activeCommands) { onCommand(MarkdownCommand.TABLE) }
                        ToolbarIconButton(ToolbarGlyphType.IMAGE, "Add image") { onAddImage() }
                    }
                }
                Spacer(Modifier.width(4.dp))
                ToolbarIconButton(ToolbarGlyphType.UNDO, "Undo") { onCommand(MarkdownCommand.UNDO) }
                ToolbarIconButton(ToolbarGlyphType.REDO, "Redo") { onCommand(MarkdownCommand.REDO) }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun ListStyleMenuPopupContent(
    visibleState: MutableTransitionState<Boolean>,
    activeCommands: Set<MarkdownCommand>,
    onSelect: (MarkdownCommand) -> Unit,
) {
    val motion = LocalKgsMotion.current
    AnimatedVisibility(
        visibleState = visibleState,
        enter = scaleIn(
            animationSpec = motion.fastSpatialSpec(),
            initialScale = .8f,
            transformOrigin = TransformOrigin(0f, 1f),
        ) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(
            animationSpec = motion.fastSpatialSpec(),
            targetScale = .8f,
            transformOrigin = TransformOrigin(0f, 1f),
        ) + fadeOut(motion.fastEffectsSpec()),
    ) {
        val listMenuShape = RoundedCornerShape(18.dp)
        Surface(
            color = Color.White,
            shape = listMenuShape,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            modifier = Modifier
                .width(238.dp)
                .testTag("list-style-menu")
                .kgsMenuShadow(listMenuShape),
        ) {
            Column {
                ListMenuItem(
                    "Bulleted list",
                    ToolbarGlyphType.LIST,
                    selected = MarkdownCommand.BULLET_LIST in activeCommands,
                ) { onSelect(MarkdownCommand.BULLET_LIST) }
                ListMenuItem(
                    "Checklist",
                    ToolbarGlyphType.CHECKLIST,
                    selected = MarkdownCommand.TASK_LIST in activeCommands,
                ) { onSelect(MarkdownCommand.TASK_LIST) }
                ListMenuItem(
                    "Numbered list",
                    ToolbarGlyphType.NUMBERED_LIST,
                    selected = MarkdownCommand.NUMBERED_LIST in activeCommands,
                ) { onSelect(MarkdownCommand.NUMBERED_LIST) }
            }
        }
    }
}

@Composable
private fun ListMenuItem(
    label: String,
    glyph: ToolbarGlyphType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    KgsTooltip(label) {
        KgsDropdownMenuItem(
            text = label,
            selected = selected,
            icon = { ToolbarGlyph(glyph, MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onClick,
        )
    }
}

@Composable
private fun ToolbarIconButton(
    glyph: ToolbarGlyphType,
    description: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    ToolbarButton(description, onClick, selected) {
        ToolbarGlyph(
            glyph,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolbarButton(
    description: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val corner by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (selected) 19.dp else 10.dp,
        animationSpec = LocalKgsMotion.current.fastSpatialSpec(),
        label = "$description shape",
    )
    KgsTooltip(description) {
        KgsExpressiveSurface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            restingCorners = KgsCornerRadii(corner),
            modifier = Modifier
                .size(38.dp)
                .semantics {
                    contentDescription = description
                    this.selected = selected
                },
        ) { content() }
    }
}

private enum class ToolbarGlyphType {
    RICH, SOURCE, BOLD, ITALIC, HEADING, LIST, CHECKLIST, NUMBERED_LIST, QUOTE, CODE, TABLE, IMAGE, UNDO, REDO,
}

private fun Modifier.kgsMenuShadow(shape: RoundedCornerShape): Modifier = shadow(
    elevation = 18.dp,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = .065f),
    spotColor = Color.Black.copy(alpha = .1f),
)

private class AboveAnchorPopupPositionProvider(
    private val horizontalOffset: Int,
    private val verticalGap: Int,
    private val windowMargin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + horizontalOffset
        } else {
            anchorBounds.right - popupContentSize.width - horizontalOffset
        }
        val maxX = (windowSize.width - popupContentSize.width - windowMargin)
            .coerceAtLeast(windowMargin)
        val x = desiredX.coerceIn(windowMargin, maxX)
        val y = (anchorBounds.top - popupContentSize.height - verticalGap)
            .coerceAtLeast(windowMargin)
        return IntOffset(x, y)
    }
}

@Composable
private fun ToolbarGlyph(type: ToolbarGlyphType, color: Color) {
    if (type in setOf(
            ToolbarGlyphType.RICH,
            ToolbarGlyphType.SOURCE,
            ToolbarGlyphType.LIST,
            ToolbarGlyphType.CODE,
        )
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = size.minDimension * .09f
            fun roundedLine(start: Offset, end: Offset) {
                drawLine(color, start, end, stroke, StrokeCap.Round)
            }
            when (type) {
                ToolbarGlyphType.RICH -> {
                    roundedLine(Offset(size.width * .22f, size.height * .29f), Offset(size.width * .78f, size.height * .29f))
                    roundedLine(Offset(size.width * .22f, size.height * .50f), Offset(size.width * .66f, size.height * .50f))
                    roundedLine(Offset(size.width * .22f, size.height * .71f), Offset(size.width * .73f, size.height * .71f))
                }
                ToolbarGlyphType.SOURCE -> {
                    roundedLine(Offset(size.width * .39f, size.height * .28f), Offset(size.width * .20f, size.height * .50f))
                    roundedLine(Offset(size.width * .20f, size.height * .50f), Offset(size.width * .39f, size.height * .72f))
                    roundedLine(Offset(size.width * .61f, size.height * .28f), Offset(size.width * .80f, size.height * .50f))
                    roundedLine(Offset(size.width * .80f, size.height * .50f), Offset(size.width * .61f, size.height * .72f))
                }
                ToolbarGlyphType.LIST -> {
                    drawCircle(color, stroke * .62f, Offset(size.width * .22f, size.height * .35f))
                    drawCircle(color, stroke * .62f, Offset(size.width * .22f, size.height * .65f))
                    roundedLine(Offset(size.width * .39f, size.height * .35f), Offset(size.width * .80f, size.height * .35f))
                    roundedLine(Offset(size.width * .39f, size.height * .65f), Offset(size.width * .80f, size.height * .65f))
                }
                ToolbarGlyphType.CODE -> {
                    roundedLine(Offset(size.width * .40f, size.height * .27f), Offset(size.width * .18f, size.height * .50f))
                    roundedLine(Offset(size.width * .18f, size.height * .50f), Offset(size.width * .40f, size.height * .73f))
                    roundedLine(Offset(size.width * .60f, size.height * .27f), Offset(size.width * .82f, size.height * .50f))
                    roundedLine(Offset(size.width * .82f, size.height * .50f), Offset(size.width * .60f, size.height * .73f))
                }
                else -> Unit
            }
        }
        return
    }
    val drawable = when (type) {
        ToolbarGlyphType.RICH -> R.drawable.ic_toolbar_rich
        ToolbarGlyphType.SOURCE -> R.drawable.ic_toolbar_source
        ToolbarGlyphType.BOLD -> R.drawable.ic_toolbar_bold
        ToolbarGlyphType.ITALIC -> R.drawable.ic_toolbar_italic
        ToolbarGlyphType.HEADING -> R.drawable.ic_toolbar_heading
        ToolbarGlyphType.LIST -> R.drawable.ic_toolbar_bulleted_list
        ToolbarGlyphType.CHECKLIST -> R.drawable.ic_toolbar_checklist
        ToolbarGlyphType.NUMBERED_LIST -> R.drawable.ic_toolbar_numbered_list
        ToolbarGlyphType.QUOTE -> R.drawable.ic_toolbar_quote
        ToolbarGlyphType.CODE -> R.drawable.ic_toolbar_code
        ToolbarGlyphType.TABLE -> R.drawable.ic_toolbar_table
        ToolbarGlyphType.IMAGE -> R.drawable.ic_toolbar_image
        ToolbarGlyphType.UNDO -> R.drawable.ic_toolbar_undo
        ToolbarGlyphType.REDO -> R.drawable.ic_toolbar_redo
    }
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(21.dp),
    )
}

private fun WebView.loadMarkdown(markdown: String) {
    evaluateJavascript(
        "window.kgsEditor?.load(${JSONObject.quote(markdown)})",
        null,
    )
}

private fun WebView.setDarkMode(enabled: Boolean) {
    evaluateJavascript("window.kgsEditor?.setDarkMode($enabled)", null)
}

private fun markdownCommandForBridgeName(name: String): MarkdownCommand? = when (name) {
    "bold" -> MarkdownCommand.BOLD
    "italic" -> MarkdownCommand.ITALIC
    "heading" -> MarkdownCommand.HEADING_2
    "bullet" -> MarkdownCommand.BULLET_LIST
    "numbered" -> MarkdownCommand.NUMBERED_LIST
    "task" -> MarkdownCommand.TASK_LIST
    "quote" -> MarkdownCommand.BLOCKQUOTE
    "code" -> MarkdownCommand.CODE_BLOCK
    "table" -> MarkdownCommand.TABLE
    else -> null
}

private fun WebView.runCommand(
    command: MarkdownCommand,
    onActiveCommandsChange: (Set<MarkdownCommand>) -> Unit,
) {
    val name = when (command) {
        MarkdownCommand.BOLD -> "bold"
        MarkdownCommand.ITALIC -> "italic"
        MarkdownCommand.HEADING_2 -> "heading"
        MarkdownCommand.BULLET_LIST -> "bullet"
        MarkdownCommand.NUMBERED_LIST -> "numbered"
        MarkdownCommand.TASK_LIST -> "task"
        MarkdownCommand.BLOCKQUOTE -> "quote"
        MarkdownCommand.CODE_BLOCK -> "code"
        MarkdownCommand.TABLE -> "table"
        MarkdownCommand.UNDO -> "undo"
        MarkdownCommand.REDO -> "redo"
    }
    evaluateJavascript("window.kgsEditor?.run(${JSONObject.quote(name)})") { result ->
        val activeCommands = runCatching {
            val values = org.json.JSONArray(result)
            buildSet {
                for (index in 0 until values.length()) {
                    markdownCommandForBridgeName(values.optString(index))?.let(::add)
                }
            }
        }.getOrNull() ?: return@evaluateJavascript
        onActiveCommandsChange(activeCommands)
    }
}

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val BRIDGE_NAME = "KgsNotesEditor"
