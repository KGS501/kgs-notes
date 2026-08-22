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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
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
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("editor-toolbar"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(
                description = if (mode == EditorMode.RICH) "Switch to Source Mode" else "Switch to Rich Mode",
                onClick = {
                    onModeChange(if (mode == EditorMode.RICH) EditorMode.SOURCE else EditorMode.RICH)
                },
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .78f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .78f))
                    },
                    label = "mode icon",
                ) { current ->
                    Text(
                        text = if (current == EditorMode.RICH) "Aa" else "</>",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (current == EditorMode.RICH) 16.sp else 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            VerticalToolbarDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                ToolbarTextButton("B", "Bold", fontWeight = FontWeight.Bold) {
                    onCommand(MarkdownCommand.BOLD)
                }
                ToolbarTextButton("I", "Italic", fontStyle = FontStyle.Italic) {
                    onCommand(MarkdownCommand.ITALIC)
                }
                ToolbarTextButton("H2", "Heading level 2", fontSize = 12.sp) {
                    onCommand(MarkdownCommand.HEADING_2)
                }
                Box {
                    ToolbarButton(description = "Lists", onClick = { listMenuOpen = true }) {
                        ListGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = listMenuOpen,
                        onDismissRequest = { listMenuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        properties = PopupProperties(focusable = false),
                    ) {
                        ListMenuItem("Bulleted list", "•") {
                            onCommand(MarkdownCommand.BULLET_LIST)
                            listMenuOpen = false
                        }
                        ListMenuItem("Checklist", "☑") {
                            onCommand(MarkdownCommand.TASK_LIST)
                            listMenuOpen = false
                        }
                        ListMenuItem("Numbered list", "1.") {
                            onCommand(MarkdownCommand.NUMBERED_LIST)
                            listMenuOpen = false
                        }
                    }
                }
                ToolbarTextButton("“", "Quote") { onCommand(MarkdownCommand.BLOCKQUOTE) }
                ToolbarTextButton("</>", "Code block", fontSize = 11.sp) {
                    onCommand(MarkdownCommand.CODE_BLOCK)
                }
                ToolbarTextButton("▦", "Insert table") { onCommand(MarkdownCommand.TABLE) }
            }
            VerticalToolbarDivider()
            ToolbarTextButton("↶", "Undo", fontSize = 20.sp) { onCommand(MarkdownCommand.UNDO) }
            ToolbarTextButton("↷", "Redo", fontSize = 20.sp) { onCommand(MarkdownCommand.REDO) }
            Spacer(Modifier.width(2.dp))
        }
    }
}

@Composable
private fun ListMenuItem(label: String, glyph: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Text(glyph, fontWeight = FontWeight.SemiBold) },
        onClick = onClick,
    )
}

@Composable
private fun ToolbarTextButton(
    text: String,
    description: String,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    onClick: () -> Unit,
) {
    ToolbarButton(description, onClick) {
        Text(
            text = text,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolbarButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) { content() }
}

@Composable
private fun VerticalToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 9.dp)
            .width(1.dp)
            .height(30.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ListGlyph(color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        listOf(.3f, .52f, .74f).forEach { y ->
            drawCircle(color, radius = stroke * .7f, center = Offset(size.width * .16f, size.height * y))
            drawLine(
                color,
                start = Offset(size.width * .33f, size.height * y),
                end = Offset(size.width * .86f, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
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
