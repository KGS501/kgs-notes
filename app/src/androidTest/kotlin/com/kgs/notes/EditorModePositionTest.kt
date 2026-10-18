package com.kgs.notes

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.editor.KgsMarkdownEditor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Full native/WebView mode handoff through the editor's Compose UI seam. */
class EditorModePositionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sourceReadingPositionSurvivesRepeatedModeSwitches() {
        show(EditorMode.SOURCE)
        compose.onNodeWithTag("source-editor").performTouchInput { swipeUp() }
        compose.onNodeWithTag("source-editor").performTouchInput { swipeUp() }
        val before = sourceScroll()
        assertTrue(before > 100)
        repeat(3) {
            compose.onNodeWithContentDescription("Switch to Rich Mode").performClick()
            waitForRich()
            assertTrue("Rich Mode must show the same part rather than the start", javascript("window.scrollY").toFloat() > 100)
            compose.onNodeWithContentDescription("Switch to Source Mode").performClick()
            compose.waitUntil(10_000) { sourceExists() && sourceScroll() > 100 }
            assertEquals("Round trips must not accumulate scroll drift", before, sourceScroll(), 1f)
        }
    }

    @Test fun richReadingPositionSurvivesRepeatedModeSwitches() {
        show(EditorMode.RICH)
        waitForRich()
        javascript("window.scrollTo(0, 1234); window.scrollY")
        val before = javascript("window.scrollY").toFloat()
        repeat(3) {
            compose.onNodeWithContentDescription("Switch to Source Mode").performClick()
            compose.waitUntil(10_000) { sourceExists() && sourceScroll() > 100 }
            compose.onNodeWithContentDescription("Switch to Rich Mode").performClick()
            waitForRich()
            assertEquals("Rich Mode must return to its exact reading position", before, javascript("window.scrollY").toFloat(), 1f)
        }
    }

    private fun show(initial: EditorMode) {
        val source = buildString {
            append("# Synthetic reference\n\n")
            repeat(100) { index ->
                append("## Section $index\n\nA paragraph with **formatted** text and enough content to wrap across lines.\n\n")
                append("| Name | Value |\n| --- | --- |\n| Item $index | Description |\n\n")
            }
        }
        var mode by mutableStateOf(initial)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            KgsNotesTheme { KgsMarkdownEditor(source, mode, onMarkdownChange = {
                assertEquals("Changing modes must not rewrite the Note", source, it)
            }, onModeChange = { mode = it }) }
        } }
    }

    private fun sourceExists() = compose.onAllNodesWithTag("source-editor").fetchSemanticsNodes().isNotEmpty()
    private fun sourceScroll() = compose.onNodeWithTag("source-editor").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun waitForRich() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
    }

    private fun javascript(script: String): String {
        val view = AtomicReference<WebView>()
        compose.runOnIdle { view.set(requireNotNull(findWebView(compose.activity.window.decorView))) }
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        compose.activity.runOnUiThread { view.get().evaluateJavascript(script) { result.set(it); completed.countDown() } }
        assertTrue("Editor probe must complete", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
