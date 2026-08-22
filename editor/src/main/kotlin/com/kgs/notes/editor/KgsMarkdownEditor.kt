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
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
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
    modifier: Modifier = Modifier,
    editor: MarkdownEditor = remember { DefaultMarkdownEditor() },
) {
    val safety = remember(markdown, editor) { editor.safetyFor(markdown) }
    val effectiveMode = when {
        requestedMode == EditorMode.SOURCE -> EditorMode.SOURCE
        safety is EditorSafety.SourceMode -> EditorMode.SOURCE
        else -> EditorMode.RICH
    }

    Column(modifier) {
        if (requestedMode == EditorMode.RICH && safety is EditorSafety.SourceMode) {
            SourceFallbackNotice(safety.reason)
        }
        AnimatedContent(
            targetState = effectiveMode,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
            },
            label = "editor mode",
            modifier = Modifier.weight(1f),
        ) { mode ->
            when (mode) {
                EditorMode.RICH -> RichMarkdownEditor(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    modifier = Modifier.fillMaxSize(),
                )

                EditorMode.SOURCE -> SourceMarkdownEditor(
                    markdown = markdown,
                    onMarkdownChange = onMarkdownChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BasicTextField(
            value = markdown,
            onValueChange = onMarkdownChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // Explicitly disable legacy file-URL access on older WebView implementations.
@Composable
private fun RichMarkdownEditor(
    markdown: String,
    onMarkdownChange: (String) -> Unit,
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
                    loadUrl("$ASSET_ORIGIN/assets/kgs-editor/index.html")
                }
            },
            update = { webView ->
                webView.setDarkMode(darkMode)
                webView.loadMarkdown(markdown)
            },
            onRelease = WebView::destroy,
            modifier = modifier,
        )
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

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val BRIDGE_NAME = "KgsNotesEditor"
