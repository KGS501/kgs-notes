package com.kgs.notes.editor

import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import com.kgs.notes.design.R.string as UiR
import java.util.Collections
import java.util.WeakHashMap

import com.kgs.notes.design.LocalKgsSheetDock
import com.kgs.notes.design.kgsSheetDock
import com.kgs.notes.design.rememberKgsDismissSheets
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
import com.kgs.notes.design.LocalKgsDarkTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kgs.notes.design.KgsBottomSheet
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
import org.json.JSONTokener
import java.io.InputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class EditorMode {
    RICH,
    SOURCE,
}

private data class EditorViewport(
    val sourceOffset: Int,
    val top: Float,
    val selectionStart: Int,
    val selectionEnd: Int,
    val scrollY: Float? = null,
    val width: Float = 0f,
    val height: Float = 0f,
) {
    fun json() = JSONObject().put("sourceOffset", sourceOffset).put("top", top)
        .put("selectionStart", selectionStart).put("selectionEnd", selectionEnd)
        .put("scrollY", scrollY).put("width", width).put("height", height)

    fun sameScroll(other: EditorViewport) = scrollY != null && other.scrollY != null &&
        kotlin.math.abs(scrollY - other.scrollY) < .5f && width == other.width && height == other.height

    companion object {
        fun from(json: JSONObject) = EditorViewport(json.getInt("sourceOffset"), json.getDouble("top").toFloat(),
            json.getInt("selectionStart"), json.getInt("selectionEnd"),
            if (json.has("scrollY") && !json.isNull("scrollY")) json.getDouble("scrollY").toFloat() else null,
            json.optDouble("width", 0.0).toFloat(), json.optDouble("height", 0.0).toFloat())
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun KgsMarkdownEditor(
    markdown: String,
    requestedMode: EditorMode,
    onMarkdownChange: (String) -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onAddImage: () -> Unit = {},
    canAddImage: Boolean = true,
    openManagedAttachment: (String) -> InputStream? = { null },
    managedAttachmentType: (String) -> String? = { null },
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    richLoadingContent: @Composable () -> Unit = {},
    editor: MarkdownEditor = remember { DefaultMarkdownEditor() },
    readOnly: Boolean = false,
    allowRemoteResources: Boolean = true,
    onSnapshotReaderReady: (suspend () -> String?) -> Unit = {},
) {
    val strings = androidx.compose.ui.platform.LocalResources.current

    val safety = remember(markdown, editor) { editor.safetyFor(markdown) }
    var rendererFailure by remember(editor) { mutableStateOf<String?>(null) }
    val richModeBlocker = when {
        rendererFailure != null -> rendererFailure
        !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ->
            strings.getString(UiR.ui_this_android_webview_requires_source_mode)
        safety is EditorSafety.SourceMode -> safety.reason
        else -> null
    }
    val effectiveMode = when {
        requestedMode == EditorMode.SOURCE -> EditorMode.SOURCE
        richModeBlocker != null -> EditorMode.SOURCE
        else -> EditorMode.RICH
    }

    val commands = remember { EditorCommandDispatcher() }
    val modeSwitchScope = rememberCoroutineScope()
    var viewport by remember { mutableStateOf<EditorViewport?>(null) }
    var switchingMode by remember { mutableStateOf(false) }
    val modeSnapshots = remember { mutableMapOf<EditorMode, Pair<String, EditorViewport>>() }
    var readingBaseline by remember { mutableStateOf<Pair<String, EditorViewport>?>(null) }
    var activeCommands by remember { mutableStateOf(emptySet<MarkdownCommand>()) }
    var richEditorReady by remember { mutableStateOf(false) }
    val richOpacity by animateFloatAsState(if (richEditorReady) 1f else 0f, tween(180), label = "Rich editor ready")
    var restoreKeyboardMode by remember { mutableStateOf<EditorMode?>(null) }
    val keyboardWasOpen = WindowInsets.isImeVisible
    val currentMarkdown by rememberUpdatedState(markdown)
    SideEffect {
        onSnapshotReaderReady {
            if (commands.hasReader) commands.readCurrentMarkdown() else if (!richEditorReady) currentMarkdown else null
        }
    }

    LaunchedEffect(effectiveMode) {
        if (effectiveMode == EditorMode.SOURCE) activeCommands = emptySet()
        richEditorReady = false
    }

    Column(
        modifier.bottomBarsOverlapPadding(),
    ) {
        if (requestedMode == EditorMode.RICH && richModeBlocker != null) {
            SourceFallbackNotice(richModeBlocker)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .then(bodyModifier)
                .clipToBounds(),
        ) {
            when (effectiveMode) {
                EditorMode.RICH -> {
                    // The real editor measures at the final body size from the
                    // first transition frame; a different text layout never replaces it.
                    RichMarkdownEditor(
                        markdown = markdown,
                        onMarkdownChange = onMarkdownChange,
                        onActiveCommandsChange = { activeCommands = it },
                        onReady = { richEditorReady = true },
                        onFallback = { rendererFailure = strings.getString(UiR.ui_this_note_couldn_t_be_rendered_reliably_source_mode_keeps_its_contents_intact) },
                        restoreFocus = restoreKeyboardMode == EditorMode.RICH,
                        onFocusRestored = { restoreKeyboardMode = null },
                        commands = commands,
                        initialViewport = viewport,
                        onViewportRestored = { readingBaseline = currentMarkdown to it },
                        openManagedAttachment = openManagedAttachment,
                        managedAttachmentType = managedAttachmentType,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = richOpacity },
                        readOnly = readOnly,
                        allowRemoteResources = allowRemoteResources,
                    )
                    androidx.compose.animation.AnimatedVisibility(!richEditorReady, enter = fadeIn(tween(120)), exit = fadeOut(tween(180))) {
                        Box(Modifier.fillMaxSize().testTag("rich-editor-loading-preview")) {
                            richLoadingContent()
                        }
                    }
                }

                EditorMode.SOURCE -> SourceMarkdownEditor(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    commands = commands,
                    editor = editor,
                    initialViewport = viewport,
                    onViewportRestored = { readingBaseline = currentMarkdown to it },
                    restoreFocus = restoreKeyboardMode == EditorMode.SOURCE,
                    onFocusRestored = { restoreKeyboardMode = null },
                    modifier = Modifier.fillMaxSize(),
                    readOnly = readOnly,
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
        if (readOnly) {
            // Read-only previews still provide a safe edge for their Source chooser sheet.
            Spacer(Modifier.fillMaxWidth().height(1.dp).kgsSheetDock(LocalKgsSheetDock.current))
        } else EditorToolbar(
            mode = effectiveMode,
            activeCommands = activeCommands,
            onModeChange = { nextMode ->
                if (!switchingMode) modeSwitchScope.launch {
                    switchingMode = true
                    try {
                        // Capture before disposing the outgoing editor, including
                        // changes whose debounced bridge notification hasn't arrived.
                        withTimeoutOrNull(1_500) {
                            val text = commands.readCurrentMarkdown() ?: currentMarkdown
                            val captured = commands.readViewport()
                            if (captured != null) {
                                val previous = modeSnapshots[nextMode]?.takeIf { it.first == text }?.second
                                val sameLocation = readingBaseline?.let { it.first == text && it.second.sameScroll(captured) } == true
                                modeSnapshots[effectiveMode] = text to captured
                                viewport = if (sameLocation && previous != null) captured.copy(
                                    scrollY = previous.scrollY, width = previous.width, height = previous.height,
                                ) else captured.copy(scrollY = null)
                            }
                            if (text != currentMarkdown) onMarkdownChange(text)
                        }
                        rendererFailure = null
                        restoreKeyboardMode = if (keyboardWasOpen) nextMode else null
                        onModeChange(nextMode)
                    } finally { switchingMode = false }
                }
            },
            onCommand = commands::dispatch,
            onAddImage = onAddImage,
            canAddImage = canAddImage,
        )
    }
}

@Composable
private fun SourceFallbackNotice(reason: String) {
    val strings = androidx.compose.ui.platform.LocalResources.current

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
            Text(strings.getString(UiR.ui_source_mode), style = MaterialTheme.typography.labelLarge)
            val explanation = when (reason) {
                "This Note is large. Source Mode keeps editing responsive." -> strings.getString(UiR.ui_this_note_is_large_source_mode_keeps_editing_responsive)
                "This Note contains characters that need Source Mode to stay intact." -> strings.getString(UiR.ui_this_note_contains_characters_that_need_source_mode_to_stay_intact)
                else -> reason
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SourceMarkdownEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    commands: EditorCommandDispatcher,
    editor: MarkdownEditor,
    initialViewport: EditorViewport?,
    onViewportRestored: (EditorViewport) -> Unit,
    restoreFocus: Boolean,
    onFocusRestored: () -> Unit,
    modifier: Modifier,
    readOnly: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val field = rememberTextFieldState(markdown, initialSelection = initialViewport?.let {
        TextRange(it.selectionStart.coerceIn(0, markdown.length), it.selectionEnd.coerceIn(0, markdown.length))
    } ?: TextRange.Zero)
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var reportedViewport by remember { mutableStateOf(false) }
    var restoringViewport by remember { mutableStateOf(initialViewport != null) }
    val opacity by animateFloatAsState(if (restoringViewport) 0f else 1f, tween(120), label = "Source editor ready")
    fun captureViewport(): EditorViewport? = textLayout?.let { layout ->
        val line = layout.getLineForVerticalPosition(scroll.value.toFloat())
        EditorViewport(layout.getLineStart(line), 16f + (layout.getLineTop(line) - scroll.value) / density,
            field.selection.start, field.selection.end, scroll.value / density,
            viewportSize.width / density, viewportSize.height / density)
    }
    LaunchedEffect(restoreFocus, textLayout, viewportSize) {
        val layout = textLayout ?: return@LaunchedEffect
        if (restoreFocus && !readOnly) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusRestored()
        }
        if (restoringViewport && initialViewport != null) {
            withFrameNanos { }
            withFrameNanos { }
            val line = layout.getLineForOffset(initialViewport.sourceOffset.coerceIn(0, field.text.length))
            val target = if (initialViewport.scrollY != null && initialViewport.width == viewportSize.width / density &&
                initialViewport.height == viewportSize.height / density) initialViewport.scrollY * density
                else layout.getLineTop(line) + (16f - initialViewport.top) * density
            scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
            restoringViewport = false
        }
        if (!reportedViewport && viewportSize != IntSize.Zero) {
            withFrameNanos { }
            captureViewport()?.let(onViewportRestored)
            reportedViewport = true
        }
    }
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
        commands.connect(commandOwner, reader = { field.text.toString() }, viewportReader = { captureViewport() }) { command ->
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
            scrollState = scroll,
            onTextLayout = { textLayout = it() },
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = opacity }
                .onSizeChanged { viewportSize = it }
                .focusRequester(focusRequester)
                // BasicTextField offers ScrollBy for its internal scroll but reports no
                // position. Publish it, like the library list does, so accessibility
                // services (and tests) can tell where in a long Note the reader is.
                .semantics {
                    verticalScrollAxisRange = ScrollAxisRange(
                        value = { scroll.value.toFloat() },
                        maxValue = { scroll.maxValue.toFloat() },
                    )
                }
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
    onFallback: () -> Unit,
    restoreFocus: Boolean,
    onFocusRestored: () -> Unit,
    commands: EditorCommandDispatcher,
    initialViewport: EditorViewport?,
    onViewportRestored: (EditorViewport) -> Unit,
    openManagedAttachment: (String) -> InputStream?,
    managedAttachmentType: (String) -> String?,
    modifier: Modifier,
    readOnly: Boolean = false,
    allowRemoteResources: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val language = resources.configuration.locales[0].toLanguageTag()
    val labels = mapOf(
        "content" to resources.getString(UiR.ui_rich_note_content),
        "empty" to resources.getString(UiR.ui_start_writing),
        "sourceTitle" to resources.getString(UiR.ui_preserved_source_edit_in_source_mode),
        "sourceHint" to resources.getString(UiR.ui_edit_this_fragment_in_source_mode),
        "NOTE" to resources.getString(UiR.ui_note),
        "TIP" to resources.getString(UiR.ui_tip),
        "IMPORTANT" to resources.getString(UiR.ui_important),
        "WARNING" to resources.getString(UiR.ui_warning),
        "CAUTION" to resources.getString(UiR.ui_caution),
        "Comment" to resources.getString(UiR.ui_comment),
        "Metadata" to resources.getString(UiR.ui_metadata),
        "Footnote" to resources.getString(UiR.ui_footnote),
        "Math" to resources.getString(UiR.ui_math),
        "Directive" to resources.getString(UiR.ui_directive),
        "Footnote reference" to resources.getString(UiR.ui_footnote_reference),
    )
    val latestLabels by rememberUpdatedState(labels)
    val latestLanguage by rememberUpdatedState(language)
    val keyboard = LocalSoftwareKeyboardController.current
    // Follows the app's chosen dark mode, which may differ from the system's.
    val darkMode = LocalKgsDarkTheme.current
    val colors = MaterialTheme.colorScheme
    val themeColors = mapOf(
        "primary" to colors.primary, "on-primary" to colors.onPrimary,
        "primary-container" to colors.primaryContainer, "surface" to colors.surface,
        "on-surface" to colors.onSurface, "surface-variant" to colors.surfaceVariant,
        "on-surface-variant" to colors.onSurfaceVariant, "outline" to colors.outline,
    ).mapValues { (_, color) -> "#%06X".format(color.toArgb() and 0xFFFFFF) }
    val latestThemeColors by rememberUpdatedState(themeColors)
    val latestMarkdown by rememberUpdatedState(markdown)
    val latestDarkMode by rememberUpdatedState(darkMode)
    val latestOnMarkdownChange by rememberUpdatedState(onMarkdownChange)
    val latestOnActiveCommandsChange by rememberUpdatedState(onActiveCommandsChange)
    val latestOnReady by rememberUpdatedState(onReady)
    val latestOnViewportRestored by rememberUpdatedState(onViewportRestored)
    val latestOnFallback by rememberUpdatedState(onFallback)
    val latestRestoreFocus by rememberUpdatedState(restoreFocus)
    val latestOnFocusRestored by rememberUpdatedState(onFocusRestored)
    val latestOpenManagedAttachment by rememberUpdatedState(openManagedAttachment)
    val latestManagedAttachmentType by rememberUpdatedState(managedAttachmentType)
    val latestReadOnly by rememberUpdatedState(readOnly)
    val latestAllowRemoteResources by rememberUpdatedState(allowRemoteResources)
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
    LaunchedEffect(labels, language, connectedWebView) {
        connectedWebView?.setEditorLabels(labels, language)
    }
    LaunchedEffect(themeColors, connectedWebView) {
        connectedWebView?.setThemeColors(themeColors)
    }
    LaunchedEffect(darkMode, connectedWebView) {
        connectedWebView?.setDarkMode(darkMode)
    }
    LaunchedEffect(readOnly, connectedWebView) {
        connectedWebView?.setReadOnly(readOnly)
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
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            // Only the fixed editor document may become a page on the bridge origin.
                            if (request.isForMainFrame) {
                                if (!EditorWebPolicy.allowsMainFrameRequest(url)) return deniedResponse()
                                val document = assetLoader.shouldInterceptRequest(request.url) ?: return deniedResponse()
                                document.responseHeaders = (document.responseHeaders.orEmpty()) + EditorWebPolicy.editorDocumentHeaders()
                                return document
                            }
                            EditorWebPolicy.attachmentRequest(url)?.let { attachmentRequest ->
                                val content = latestOpenManagedAttachment(attachmentRequest.id)
                                    ?: return deniedResponse(404, "Not Found")
                                val storedType = runCatching { latestManagedAttachmentType(attachmentRequest.id) }.getOrNull()
                                val typed = EditorWebPolicy.typedAttachment(attachmentRequest, storedType, content)
                                    ?: return deniedResponse()
                                return WebResourceResponse(typed.mediaType, null, 200, "OK",
                                    EditorWebPolicy.attachmentHeaders(), typed.data)
                            }
                            return assetLoader.shouldInterceptRequest(request.url)
                                ?: if (!latestAllowRemoteResources) WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf())) else null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = !EditorWebPolicy.allowsTopLevelNavigation(request.url.toString())

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: SslErrorHandler,
                            error: android.net.http.SslError,
                        ) = handler.cancel()

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            view.destroyEditor()
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
                            if (!isMainFrame || sourceOrigin != Uri.parse(ASSET_ORIGIN) ||
                                !EditorWebPolicy.isEditorDocument(view.url)
                            ) {
                                return@addWebMessageListener
                            }
                            val payload = runCatching {
                                JSONObject(message.data ?: return@addWebMessageListener)
                            }.getOrNull() ?: return@addWebMessageListener
                            when (payload.optString("type")) {
                                "fallback" -> if (connectedWebView === view) latestOnFallback()
                                "viewportRestored" -> if (connectedWebView === view) {
                                    val restored = runCatching { EditorViewport.from(payload.getJSONObject("viewport")) }.getOrNull()
                                    view.postVisualStateCallbackIfAlive(0L, object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            if (connectedWebView === view) {
                                                restored?.let(latestOnViewportRestored)
                                                latestOnReady()
                                            }
                                        }
                                    })
                                }
                                "ready" -> {
                                    view.setEditorLabels(latestLabels, latestLanguage)
                                    view.setThemeColors(latestThemeColors)
                                    view.setDarkMode(latestDarkMode)
                                    view.setReadOnly(latestReadOnly)
                                    editorMarkdown = latestMarkdown
                                    view.loadMarkdown(latestMarkdown) {
                                        // JavaScript readiness precedes the rendered Note.
                                        // Reveal only a frame containing the loaded Markdown.
                                        view.setReadOnly(latestReadOnly, force = latestReadOnly) {
                                            view.postVisualStateCallbackIfAlive(0L, object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (connectedWebView === view) {
                                                        if (initialViewport == null) latestOnReady()
                                                        else {
                                                            val notify = "window.$BRIDGE_NAME.postMessage(JSON.stringify({type:'viewportRestored',viewport:window.kgsEditor.viewport()}))"
                                                            view.evaluateJavascript(
                                                                "window.kgsEditor.restoreViewport(${initialViewport.json()}).then(() => {$notify}).catch(() => window.$BRIDGE_NAME.postMessage(JSON.stringify({type:'fallback'})))", null,
                                                            )
                                                        }
                                                    }
                                                }
                                            })
                                        }
                                    }
                                    commands.connect(view, reader = {
                                        suspendCancellableCoroutine<String?> { continuation ->
                                            try {
                                                view.evaluateJavascript("window.kgsEditor?.markdown()") { encoded ->
                                                    if (continuation.isActive) {
                                                        val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                                                        if (text != null) editorMarkdown = text
                                                        continuation.resume(text)
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                if (continuation.isActive) continuation.resume(null)
                                            }
                                        }
                                    }, viewportReader = {
                                        suspendCancellableCoroutine<EditorViewport?> { continuation ->
                                            try {
                                                view.evaluateJavascript("JSON.stringify(window.kgsEditor?.viewport())") { encoded ->
                                                    if (continuation.isActive) continuation.resume(runCatching {
                                                        val json = JSONObject(JSONTokener(encoded).nextValue() as String)
                                                        EditorViewport.from(json)
                                                    }.getOrNull())
                                                }
                                            } catch (_: Exception) {
                                                if (continuation.isActive) continuation.resume(null)
                                            }
                                        }
                                    }) { command ->
                                        if (!latestReadOnly) view.runCommand(command)
                                    }
                                    connectedWebView = view
                                    if (latestRestoreFocus && !latestReadOnly) {
                                        view.requestFocus()
                                        view.evaluateJavascript("window.kgsEditor?.focus()") { keyboard?.show() }
                                        latestOnFocusRestored()
                                    }
                                }
                                "changed" -> {
                                    if (connectedWebView !== view) return@addWebMessageListener
                                    val changed = payload.optString("markdown")
                                    editorMarkdown = changed
                                    if (!latestReadOnly) latestOnMarkdownChange(changed)
                                }
                                "formatting" -> if (connectedWebView === view) latestOnActiveCommandsChange(
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
                    loadUrl(EditorWebPolicy.EDITOR_URL)
                }
            },
            update = {},
            onRelease = { webView ->
                commands.disconnect(webView)
                if (connectedWebView === webView) connectedWebView = null
                webView.destroyEditor()
            },
            modifier = modifier,
        )
    }
}

private fun deniedResponse(status: Int = 403, reason: String = "Forbidden") = WebResourceResponse(
    "text/plain", "UTF-8", status, reason, EditorWebPolicy.deniedHeaders(), java.io.ByteArrayInputStream(byteArrayOf()),
)

private class EditorCommandDispatcher {
    private var owner: Any? = null
    private var action: ((MarkdownCommand) -> Unit)? = null
    private var reader: (suspend () -> String?)? = null
    private var viewportReader: (suspend () -> EditorViewport?)? = null
    private val pending = ArrayDeque<MarkdownCommand>()
    val hasReader: Boolean get() = reader != null

    fun connect(owner: Any, reader: (suspend () -> String?)? = null,
        viewportReader: (suspend () -> EditorViewport?)? = null, action: (MarkdownCommand) -> Unit) {
        this.owner = owner
        this.action = action
        this.reader = reader
        this.viewportReader = viewportReader
        while (pending.isNotEmpty()) action(pending.removeFirst())
    }

    fun disconnect(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            action = null
            reader = null
            viewportReader = null
        }
    }

    suspend fun readCurrentMarkdown(): String? = reader?.invoke()
    suspend fun readViewport(): EditorViewport? = viewportReader?.invoke()

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
    canAddImage: Boolean,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current

    val motion = LocalKgsMotion.current
    var listMenuOpen by remember { mutableStateOf(false) }
    val dismissSheets = rememberKgsDismissSheets()
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .kgsSheetDock(LocalKgsSheetDock.current)
            .testTag("editor-toolbar"),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(46.dp),
            ) {
                Spacer(Modifier.width(9.dp))
                ToolbarButton(
                    description = if (mode == EditorMode.RICH) strings.getString(UiR.ui_switch_to_source_mode) else strings.getString(UiR.ui_switch_to_rich_mode),
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
                        ToolbarIconButton(ToolbarGlyphType.BOLD, strings.getString(UiR.ui_bold), MarkdownCommand.BOLD in activeCommands) { onCommand(MarkdownCommand.BOLD) }
                        ToolbarIconButton(ToolbarGlyphType.ITALIC, strings.getString(UiR.ui_italic), MarkdownCommand.ITALIC in activeCommands) { onCommand(MarkdownCommand.ITALIC) }
                        ToolbarIconButton(ToolbarGlyphType.HEADING, strings.getString(UiR.ui_heading_level_2), MarkdownCommand.HEADING_2 in activeCommands) { onCommand(MarkdownCommand.HEADING_2) }
                        Box {
                        val activeList = listOf(
                            MarkdownCommand.BULLET_LIST,
                            MarkdownCommand.TASK_LIST,
                            MarkdownCommand.NUMBERED_LIST,
                        ).firstOrNull { it in activeCommands }
                        ToolbarButton(
                            description = strings.getString(UiR.ui_lists),
                            selected = listMenuOpen || activeList != null,
                            onClick = {
                                val wasOpen = listMenuOpen
                                dismissSheets()
                                listMenuOpen = !wasOpen
                            },
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
                        KgsBottomSheet(
                            expanded = listMenuOpen,
                            onDismissRequest = { listMenuOpen = false },
                            focusable = false,
                            modifier = Modifier.testTag("list-style-menu"),
                            title = strings.getString(UiR.ui_lists),
                        ) {
                            ListMenuItem(strings.getString(UiR.ui_bulleted_list), ToolbarGlyphType.LIST,
                                selected = MarkdownCommand.BULLET_LIST in activeCommands) {
                                onCommand(MarkdownCommand.BULLET_LIST)
                                listMenuOpen = false
                            }
                            ListMenuItem(strings.getString(UiR.ui_checklist), ToolbarGlyphType.CHECKLIST,
                                selected = MarkdownCommand.TASK_LIST in activeCommands) {
                                onCommand(MarkdownCommand.TASK_LIST)
                                listMenuOpen = false
                            }
                            ListMenuItem(strings.getString(UiR.ui_numbered_list), ToolbarGlyphType.NUMBERED_LIST,
                                selected = MarkdownCommand.NUMBERED_LIST in activeCommands) {
                                onCommand(MarkdownCommand.NUMBERED_LIST)
                                listMenuOpen = false
                            }
                        }
                        }
                        ToolbarIconButton(ToolbarGlyphType.QUOTE, strings.getString(UiR.ui_quote), MarkdownCommand.BLOCKQUOTE in activeCommands) { onCommand(MarkdownCommand.BLOCKQUOTE) }
                        ToolbarIconButton(ToolbarGlyphType.CODE, strings.getString(UiR.ui_code_block), MarkdownCommand.CODE_BLOCK in activeCommands) { onCommand(MarkdownCommand.CODE_BLOCK) }
                        ToolbarIconButton(ToolbarGlyphType.TABLE, strings.getString(UiR.ui_insert_table), MarkdownCommand.TABLE in activeCommands) { onCommand(MarkdownCommand.TABLE) }
                        if (canAddImage) {
                            ToolbarIconButton(ToolbarGlyphType.IMAGE, strings.getString(UiR.ui_add_image)) { onAddImage() }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                ToolbarIconButton(ToolbarGlyphType.UNDO, strings.getString(UiR.ui_undo)) { onCommand(MarkdownCommand.UNDO) }
                ToolbarIconButton(ToolbarGlyphType.REDO, strings.getString(UiR.ui_redo)) { onCommand(MarkdownCommand.REDO) }
                Spacer(Modifier.width(2.dp))
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
            showIndication = false,
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

private fun WebView.loadMarkdown(markdown: String, onLoaded: (() -> Unit)? = null) {
    evaluateJavascript(
        "window.kgsEditor?.load(${JSONObject.quote(markdown)})",
    ) { onLoaded?.invoke() }
}

private fun WebView.setEditorLabels(labels: Map<String, String>, language: String) {
    evaluateJavascript("window.kgsEditor?.setLabels(${JSONObject(labels)}, ${JSONObject.quote(language)})", null)
}

private fun WebView.setThemeColors(colors: Map<String, String>) {
    val properties = JSONObject(colors).toString()
    evaluateJavascript(
        "Object.entries($properties).forEach(([name, value]) => document.documentElement.style.setProperty('--kgs-' + name, value))",
        null,
    )
}

// Asynchronous JavaScript callbacks can arrive after the editor left composition or its renderer died.
// WebView.postVisualStateCallback throws on a destroyed WebView, so such late callbacks are dropped.
private val destroyedEditorWebViews: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

private fun WebView.destroyEditor() {
    destroyedEditorWebViews += this
    destroy()
}

private fun WebView.postVisualStateCallbackIfAlive(requestId: Long, callback: WebView.VisualStateCallback) {
    if (this !in destroyedEditorWebViews) postVisualStateCallback(requestId, callback)
}

private fun WebView.setDarkMode(enabled: Boolean) {
    evaluateJavascript("window.kgsEditor?.setDarkMode($enabled)", null)
}

private fun WebView.setReadOnly(readOnly: Boolean, force: Boolean = false, onApplied: (() -> Unit)? = null) {
    // Ordinary writable editors need no extra bridge round-trip during startup.
    if (!force && (tag == readOnly || (tag == null && !readOnly))) {
        tag = readOnly
        onApplied?.invoke()
        return
    }
    tag = readOnly
    // The pinned Tiptap Editor attaches itself to its root DOM element. Avoid
    // rebuilding the renderer or replacing rich text with a source-only preview.
    evaluateJavascript(
        "document.querySelector('.rich-editor')?.editor?.setEditable(${!readOnly}, false);" +
            "document.querySelectorAll('.rich-editor img, .rich-editor input').forEach(el => el.style.pointerEvents = ${JSONObject.quote(if (readOnly) "none" else "")})",
        { onApplied?.invoke() },
    )
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
    evaluateJavascript("window.kgsEditor?.run(${JSONObject.quote(name)})", null)
}

private const val ASSET_ORIGIN = EditorWebPolicy.ASSET_ORIGIN
private const val BRIDGE_NAME = "KgsNotesEditor"

/**
 * Keeps content above the keyboard and navigation bar only where they actually cover it.
 * An editor above a tabletop fold stays whole while the keyboard covers the lower half.
 */
@Composable
private fun Modifier.bottomBarsOverlapPadding(): Modifier {
    val bars = WindowInsets.ime.union(WindowInsets.navigationBars)
    val view = LocalView.current
    var top by remember { mutableIntStateOf(0) }
    return onPlaced { top = it.positionInWindow().y.roundToInt() }
        .layout { measurable, constraints ->
            val height = constraints.maxHeight
            val coveredFrom = view.rootView.height - bars.getBottom(this)
            val overlap = if (constraints.hasBoundedHeight) (top + height - coveredFrom).coerceIn(0, height) else 0
            val placeable = measurable.measure(constraints.copy(
                minHeight = (constraints.minHeight - overlap).coerceAtLeast(0),
                maxHeight = if (constraints.hasBoundedHeight) height - overlap else constraints.maxHeight,
            ))
            layout(placeable.width, placeable.height + overlap) { placeable.place(0, 0) }
        }
}
