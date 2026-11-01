package com.kgs.notes

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.editor.KgsMarkdownEditor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Rich Mode WebView boundaries on the real platform WebView: settings, navigation
 * policy, the bundled Content Security Policy, and the origin-scoped bridge.
 *
 * Probes stay inside the editor page and use reserved `.invalid` hosts, so no
 * request can reach a real server.
 */
class EditorWebViewSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun webViewSettingsDenyLocalFileAndMixedContentAccess() {
        showRichEditor()
        onUi { webView ->
            val settings = webView.settings
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION")
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertFalse(settings.domStorageEnabled)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        }
    }

    @Test fun navigationPolicyKeepsTheEditorOnTheBundledOrigin() {
        showRichEditor()
        onUi { webView ->
            val client = webView.webViewClient
            for (url in listOf(
                "https://example.invalid/",
                "http://appassets.androidplatform.net.example.invalid/",
                "file:///android_asset/kgs-editor/index.html",
                "content://com.kgs501.kgsnotes.invalid/note.md",
                "intent://example.invalid/#Intent;scheme=https;end",
                "javascript:alert(1)",
                "data:text/html,<p>probe</p>",
            )) {
                assertTrue("Navigation to $url must be cancelled", client.shouldOverrideUrlLoading(webView, request(url)))
            }
            assertFalse(client.shouldOverrideUrlLoading(webView, request("$AssetOrigin/assets/kgs-editor/index.html")))
        }
    }

    @Test fun scriptInitiatedNavigationDoesNotLeaveTheEditor() {
        showRichEditor()
        val origin = javascript("location.origin")
        assertEquals("\"$AssetOrigin\"", origin)
        javascript("location.href = 'https://example.invalid/kgs-navigation-probe'; 'started'")
        javascript("window.open('https://example.invalid/kgs-window-probe'); 'started'")
        // Give the cancelled navigations time to commit if the policy failed.
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertEquals(origin, javascript("location.origin"))
        assertEquals("\"object\"", javascript("typeof window.kgsEditor"))
    }

    @Test fun contentSecurityPolicyBlocksInjectedScriptsEvalAndNetwork() {
        showRichEditor()
        javascript(
            """
            window.__kgsCsp = [];
            document.addEventListener('securitypolicyviolation', e => window.__kgsCsp.push(e.effectiveDirective));
            const inline = document.createElement('script');
            inline.textContent = 'window.__kgsInlineRan = true';
            document.head.appendChild(inline);
            try { eval('window.__kgsEvalRan = true'); } catch (e) { window.__kgsEvalBlocked = e.name; }
            window.__kgsFetch = 'pending';
            fetch('https://example.invalid/kgs-csp-probe').then(() => window.__kgsFetch = 'loaded', () => window.__kgsFetch = 'blocked');
            const handler = document.createElement('img');
            handler.setAttribute('onerror', 'window.__kgsHandlerRan = true');
            handler.src = 'data:,not-an-image';
            document.body.appendChild(handler);
            'started'
            """.trimIndent(),
        )
        compose.waitUntil(5_000) { javascript("window.__kgsFetch") != "\"pending\"" }
        compose.waitForIdle()
        assertEquals("\"blocked\"", javascript("window.__kgsFetch"))
        assertEquals("Inline <script> must not run", "false", javascript("window.__kgsInlineRan === true"))
        assertEquals("eval must not run", "false", javascript("window.__kgsEvalRan === true"))
        assertEquals("\"EvalError\"", javascript("window.__kgsEvalBlocked"))
        assertEquals("Inline event handlers must not run", "false", javascript("window.__kgsHandlerRan === true"))
        val violations = javascript("JSON.stringify(window.__kgsCsp)")
        // Older WebViews report script-src rather than script-src-elem.
        for (directive in listOf("script-src", "connect-src")) {
            assertTrue("Expected a $directive violation in $violations", violations.contains(directive))
        }
    }

    @Test fun bridgeIsExposedOnlyToTheBundledMainFrame() {
        showRichEditor()
        assertEquals("\"object\"", javascript("typeof window.KgsNotesEditor"))
        // CSP frame-src 'none' prevents any foreign document from being framed at all.
        javascript(
            """
            window.__kgsFrame = 'pending';
            const frame = document.createElement('iframe');
            frame.onload = () => {
              try { window.__kgsFrame = typeof frame.contentWindow.KgsNotesEditor; }
              catch (e) { window.__kgsFrame = 'cross-origin'; }
            };
            frame.src = 'https://example.invalid/kgs-frame-probe';
            document.body.appendChild(frame);
            setTimeout(() => { if (window.__kgsFrame === 'pending') window.__kgsFrame = 'not-loaded'; }, 2000);
            'started'
            """.trimIndent(),
        )
        compose.waitUntil(5_000) { javascript("window.__kgsFrame") != "\"pending\"" }
        assertTrue(
            "A framed document must never see the bridge",
            javascript("window.__kgsFrame") in setOf("\"not-loaded\"", "\"cross-origin\"", "\"undefined\""),
        )
    }

    @Test fun blockedRemoteResourcesReturnAnEmptyResponse() {
        showRichEditor(allowRemoteResources = false)
        onUi { webView ->
            val client = webView.webViewClient
            val blocked = client.shouldInterceptRequest(webView, request("https://tracker.invalid/pixel.png"))
            assertNotNull("Remote requests must be answered locally when remote resources are off", blocked)
            assertEquals(-1, blocked!!.data.read())
            val bundled = client.shouldInterceptRequest(webView, request("$AssetOrigin/assets/kgs-editor/index.html"))
            assertNotNull(bundled)
            assertTrue(bundled!!.data.use { it.readBytes() }.decodeToString().contains("Content-Security-Policy"))
            assertNull(
                "Unknown attachment identifiers must not be served",
                client.shouldInterceptRequest(
                    webView,
                    request("$AssetOrigin/n/.kgs-notes-attachments/v/00000000-0000-0000-0000-000000000000.png"),
                )?.data?.read()?.takeIf { it >= 0 },
            )
        }
    }

    private fun showRichEditor(allowRemoteResources: Boolean = true) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsMarkdownEditor(
                        markdown = "# Security probe\n\nA paragraph.\n",
                        requestedMode = EditorMode.RICH,
                        onMarkdownChange = {},
                        onModeChange = {},
                        allowRemoteResources = allowRemoteResources,
                    )
                }
            }
        }
        compose.waitUntil(20_000) { richReady() || compose.onAllNodesWithTag("source-editor").fetchSemanticsNodes().isNotEmpty() }
        // Old system WebViews without WebMessageListener only offer Source Mode.
        assumeTrue("Rich Mode is unavailable with this system WebView", richReady())
    }

    private fun richReady() = compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()

    private fun onUi(block: (WebView) -> Unit) {
        val failure = AtomicReference<Throwable>()
        compose.runOnIdle {
            runCatching { block(requireNotNull(findWebView(compose.activity.window.decorView))) }
                .onFailure(failure::set)
        }
        failure.get()?.let { throw it }
    }

    private fun javascript(script: String): String {
        val view = AtomicReference<WebView>()
        compose.runOnIdle { view.set(requireNotNull(findWebView(compose.activity.window.decorView))) }
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        compose.activity.runOnUiThread {
            view.get().evaluateJavascript(script) { result.set(it); completed.countDown() }
        }
        assertTrue("Editor probe must complete", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private companion object {
        const val AssetOrigin = "https://appassets.androidplatform.net"
    }
}
