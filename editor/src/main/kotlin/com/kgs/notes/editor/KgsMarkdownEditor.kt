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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import com.kgs.notes.design.kgsClickable
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

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

    Column(modifier) {
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
                    commands = commands,
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
        }
        EditorToolbar(
            mode = effectiveMode,
            onModeChange = onModeChange,
            onCommand = commands::dispatch,
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
    var field by remember { mutableStateOf(TextFieldValue(markdown)) }
    val undo = remember { ArrayDeque<TextFieldValue>() }
    val redo = remember { ArrayDeque<TextFieldValue>() }

    LaunchedEffect(markdown) {
        if (markdown != field.text) {
            field = field.copy(
                text = markdown,
                selection = TextRange(
                    field.selection.start.coerceAtMost(markdown.length),
                    field.selection.end.coerceAtMost(markdown.length),
                ),
            )
        }
    }
    SideEffect {
        commands.action = { command ->
            when (command) {
                MarkdownCommand.UNDO -> undo.removeLastOrNull()?.let { previous ->
                    redo.addLast(field)
                    field = previous
                    onMarkdownChange(previous.text)
                }
                MarkdownCommand.REDO -> redo.removeLastOrNull()?.let { next ->
                    undo.addLast(field)
                    field = next
                    onMarkdownChange(next.text)
                }
                else -> {
                    val edit = editor.applyCommand(
                        markdown = field.text,
                        selection = MarkdownSelection(field.selection.start, field.selection.end),
                        command = command,
                    )
                    if (edit.markdown != field.text) {
                        undo.addLast(field)
                        redo.clear()
                        field = TextFieldValue(
                            text = edit.markdown,
                            selection = TextRange(edit.selection.start, edit.selection.end),
                        )
                        onMarkdownChange(edit.markdown)
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = { updated ->
                if (updated.text != field.text) {
                    undo.addLast(field)
                    redo.clear()
                    onMarkdownChange(updated.text)
                }
                field = updated
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
@Suppress("DEPRECATION") // Explicitly disable legacy file-URL access on older WebView implementations.
@Composable
private fun RichMarkdownEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
    commands: EditorCommandDispatcher,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val darkMode = isSystemInDarkTheme()
    val latestMarkdown by rememberUpdatedState(markdown)
    val latestDarkMode by rememberUpdatedState(darkMode)
    val latestOnMarkdownChange by rememberUpdatedState(onMarkdownChange)
    var rendererGeneration by remember { mutableIntStateOf(0) }

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
                        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

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
                                    view.loadMarkdown(latestMarkdown)
                                }
                                "changed" -> latestOnMarkdownChange(payload.optString("markdown"))
                            }
                        }
                    }
                    loadUrl("$ASSET_ORIGIN/assets/kgs-editor/index.html")
                }
            },
            update = { webView ->
                commands.action = webView::runCommand
                webView.setDarkMode(darkMode)
                webView.loadMarkdown(markdown)
            },
            onRelease = WebView::destroy,
            modifier = modifier,
        )
    }
}

private class EditorCommandDispatcher {
    var action: (MarkdownCommand) -> Unit = {}

    fun dispatch(command: MarkdownCommand) = action(command)
}

@Composable
private fun EditorToolbar(
    mode: EditorMode,
    onModeChange: (EditorMode) -> Unit,
    onCommand: (MarkdownCommand) -> Unit,
) {
    var listMenuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                            (fadeIn(spring(dampingRatio = .72f, stiffness = 500f)) +
                                scaleIn(spring(dampingRatio = .62f, stiffness = 420f), initialScale = .72f)) togetherWith
                                (fadeOut(tween(90)) + scaleOut(tween(110), targetScale = .72f))
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    ToolbarIconButton(ToolbarGlyphType.BOLD, "Bold") { onCommand(MarkdownCommand.BOLD) }
                    ToolbarIconButton(ToolbarGlyphType.ITALIC, "Italic") { onCommand(MarkdownCommand.ITALIC) }
                    ToolbarIconButton(ToolbarGlyphType.HEADING, "Heading level 2") { onCommand(MarkdownCommand.HEADING_2) }
                    Box {
                        ToolbarButton(description = "Lists", onClick = { listMenuOpen = true }) {
                            ToolbarGlyph(ToolbarGlyphType.LIST, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = listMenuOpen,
                            onDismissRequest = { listMenuOpen = false },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            properties = PopupProperties(focusable = false),
                        ) {
                            ListMenuItem("Bulleted list", ToolbarGlyphType.LIST) {
                                onCommand(MarkdownCommand.BULLET_LIST)
                                listMenuOpen = false
                            }
                            ListMenuItem("Checklist", ToolbarGlyphType.CHECKLIST) {
                                onCommand(MarkdownCommand.TASK_LIST)
                                listMenuOpen = false
                            }
                            ListMenuItem("Numbered list", ToolbarGlyphType.NUMBERED_LIST) {
                                onCommand(MarkdownCommand.NUMBERED_LIST)
                                listMenuOpen = false
                            }
                        }
                    }
                    ToolbarIconButton(ToolbarGlyphType.QUOTE, "Quote") { onCommand(MarkdownCommand.BLOCKQUOTE) }
                    ToolbarIconButton(ToolbarGlyphType.CODE, "Code block") { onCommand(MarkdownCommand.CODE_BLOCK) }
                    ToolbarIconButton(ToolbarGlyphType.TABLE, "Insert table") { onCommand(MarkdownCommand.TABLE) }
                }
                Spacer(Modifier.width(4.dp))
                ToolbarIconButton(ToolbarGlyphType.UNDO, "Undo") { onCommand(MarkdownCommand.UNDO) }
                ToolbarIconButton(ToolbarGlyphType.REDO, "Redo") { onCommand(MarkdownCommand.REDO) }
                Spacer(Modifier.width(2.dp))
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ListMenuItem(label: String, glyph: ToolbarGlyphType, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { ToolbarGlyph(glyph, MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

@Composable
private fun ToolbarIconButton(
    glyph: ToolbarGlyphType,
    description: String,
    onClick: () -> Unit,
) {
    ToolbarButton(description, onClick) {
        ToolbarGlyph(glyph, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolbarButton(
    description: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = if (selected) 3.dp else 0.dp)
            .size(38.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = shape,
            )
            .kgsClickable(onClick, shape)
            .semantics { contentDescription = description },
    ) { content() }
}

private enum class ToolbarGlyphType {
    RICH, SOURCE, BOLD, ITALIC, HEADING, LIST, CHECKLIST, NUMBERED_LIST, QUOTE, CODE, TABLE, UNDO, REDO,
}

@Composable
private fun ToolbarGlyph(type: ToolbarGlyphType, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = size.minDimension * .105f
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun horizontal(y: Float, start: Float = .2f, end: Float = .8f) =
            drawLine(color, Offset(size.width * start, size.height * y), Offset(size.width * end, size.height * y), stroke, StrokeCap.Round)
        when (type) {
            ToolbarGlyphType.RICH -> {
                horizontal(.28f, .16f, .62f)
                horizontal(.5f, .16f, .84f)
                horizontal(.72f, .16f, .7f)
                drawCircle(color, stroke * .54f, Offset(size.width * .8f, size.height * .24f))
            }
            ToolbarGlyphType.SOURCE, ToolbarGlyphType.CODE -> {
                drawLine(color, Offset(size.width * .36f, size.height * .28f), Offset(size.width * .16f, size.height * .5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .16f, size.height * .5f), Offset(size.width * .36f, size.height * .72f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .64f, size.height * .28f), Offset(size.width * .84f, size.height * .5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .84f, size.height * .5f), Offset(size.width * .64f, size.height * .72f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .57f, size.height * .19f), Offset(size.width * .43f, size.height * .81f), stroke * .8f, StrokeCap.Round)
            }
            ToolbarGlyphType.BOLD -> {
                val path = Path().apply {
                    moveTo(size.width * .28f, size.height * .18f)
                    lineTo(size.width * .28f, size.height * .82f)
                    moveTo(size.width * .28f, size.height * .2f)
                    cubicTo(size.width * .73f, size.height * .16f, size.width * .75f, size.height * .49f, size.width * .3f, size.height * .5f)
                    cubicTo(size.width * .8f, size.height * .49f, size.width * .8f, size.height * .84f, size.width * .28f, size.height * .8f)
                }
                drawPath(path, color, style = line)
            }
            ToolbarGlyphType.ITALIC -> {
                horizontal(.2f, .42f, .78f)
                horizontal(.8f, .22f, .58f)
                drawLine(color, Offset(size.width * .62f, size.height * .2f), Offset(size.width * .38f, size.height * .8f), stroke, StrokeCap.Round)
            }
            ToolbarGlyphType.HEADING -> {
                drawLine(color, Offset(size.width * .18f, size.height * .2f), Offset(size.width * .18f, size.height * .8f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .58f, size.height * .2f), Offset(size.width * .58f, size.height * .8f), stroke, StrokeCap.Round)
                horizontal(.5f, .18f, .58f)
                horizontal(.7f, .7f, .88f)
                horizontal(.82f, .7f, .88f)
            }
            ToolbarGlyphType.LIST, ToolbarGlyphType.CHECKLIST, ToolbarGlyphType.NUMBERED_LIST -> {
                listOf(.28f, .5f, .72f).forEachIndexed { index, y ->
                    if (type == ToolbarGlyphType.CHECKLIST) {
                        drawRoundRect(color, Offset(size.width * .1f, size.height * (y - .065f)), androidx.compose.ui.geometry.Size(size.width * .13f, size.height * .13f), style = Stroke(stroke * .62f))
                        if (index == 0) {
                            drawLine(color, Offset(size.width * .12f, size.height * y), Offset(size.width * .16f, size.height * (y + .035f)), stroke * .55f, StrokeCap.Round)
                            drawLine(color, Offset(size.width * .16f, size.height * (y + .035f)), Offset(size.width * .22f, size.height * (y - .04f)), stroke * .55f, StrokeCap.Round)
                        }
                    } else if (type == ToolbarGlyphType.NUMBERED_LIST) {
                        horizontal(y, .1f, if (index == 0) .15f else .2f)
                    } else {
                        drawCircle(color, stroke * .48f, Offset(size.width * .16f, size.height * y))
                    }
                    horizontal(y, .34f, .86f)
                }
            }
            ToolbarGlyphType.QUOTE -> {
                listOf(.3f, .62f).forEach { x ->
                    val path = Path().apply {
                        moveTo(size.width * x, size.height * .3f)
                        cubicTo(size.width * (x - .14f), size.height * .42f, size.width * (x - .13f), size.height * .7f, size.width * (x + .02f), size.height * .71f)
                        lineTo(size.width * (x + .08f), size.height * .55f)
                    }
                    drawPath(path, color, style = line)
                }
            }
            ToolbarGlyphType.TABLE -> {
                drawRoundRect(color, Offset(size.width * .14f, size.height * .19f), androidx.compose.ui.geometry.Size(size.width * .72f, size.height * .62f), style = Stroke(stroke * .8f))
                horizontal(.42f, .14f, .86f)
                horizontal(.62f, .14f, .86f)
                drawLine(color, Offset(size.width * .5f, size.height * .19f), Offset(size.width * .5f, size.height * .81f), stroke * .8f, StrokeCap.Round)
            }
            ToolbarGlyphType.UNDO, ToolbarGlyphType.REDO -> {
                val mirror = if (type == ToolbarGlyphType.UNDO) 1f else -1f
                drawArc(color, if (mirror > 0) 195f else -15f, 235f * mirror, false, style = Stroke(stroke, cap = StrokeCap.Round))
                val x = if (type == ToolbarGlyphType.UNDO) .18f else .82f
                drawLine(color, Offset(size.width * x, size.height * .42f), Offset(size.width * (x + .12f * mirror), size.height * .23f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * x, size.height * .42f), Offset(size.width * (x + .18f * mirror), size.height * .48f), stroke, StrokeCap.Round)
            }
        }
    }
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

private fun WebView.runCommand(command: MarkdownCommand) {
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

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val BRIDGE_NAME = "KgsNotesEditor"
