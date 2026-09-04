package com.kgs.notes

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.espresso.Espresso
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSyncState
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InteractionRegressionTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingOutsideSearchClearsItsFocus() {
        showLibrary()

        compose.onNodeWithTag("notes-search").performClick().assertIsFocused()
        compose.onNodeWithTag("empty-library").performTouchInput { click(center) }

        compose.onNodeWithTag("notes-search").assertIsNotFocused()
    }

    @Test
    fun tappingTheLibraryHeaderClearsSearchFocus() {
        showLibrary()

        compose.onNodeWithTag("notes-search").performClick().assertIsFocused()
        compose.onNodeWithText("Notes").performTouchInput { click(center) }

        compose.onNodeWithTag("notes-search").assertIsNotFocused()
    }

    @Test
    fun categorySearchUsesTheSameFocusedShapeChangeAsLibrarySearch() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(selectedNote = sampleNote("A note")),
                        actions = NotesActions(),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Choose Category").performClick()

        val search = compose.onNodeWithTag("category-search").performClick().assertIsFocused()
        val focusedShape = search.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.Shape,
        ]

        assertTrue(
            "The focused popup search must use the same rounded active shape",
            focusedShape.toString().contains("28.0.dp"),
        )
    }

    @Test
    fun backClearsSearchFocusBeforeLeavingTheLibrary() {
        showLibrary()

        compose.onNodeWithTag("notes-search").performClick().assertIsFocused()
        Espresso.pressBack()

        compose.onNodeWithTag("notes-search").assertIsDisplayed().assertIsNotFocused()
    }

    @Test
    fun richChecklistKeepsCustomCheckboxAndTextOnTheSameLine() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(
                            selectedNote = sampleNote("- [x] Carry the map"),
                            editorMode = EditorMode.RICH,
                        ),
                        actions = NotesActions(),
                    )
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
        }

        val webView = AtomicReference<WebView>()
        compose.runOnIdle {
            webView.set(requireNotNull(findWebView(compose.activity.window.decorView)))
        }
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        compose.activity.runOnUiThread {
            webView.get().evaluateJavascript(
                """
                    (() => {
                      const item = document.querySelector('ul[data-type="taskList"] > li');
                      const box = item?.querySelector('input[type="checkbox"]');
                      const text = item?.querySelector('p');
                      if (!box || !text) return false;
                      const boxRect = box.getBoundingClientRect();
                      const textRect = text.getBoundingClientRect();
                      const style = getComputedStyle(box);
                      const centerDelta = Math.abs(
                        boxRect.top + boxRect.height / 2 -
                        (textRect.top + parseFloat(getComputedStyle(text).lineHeight) / 2)
                      );
                      return centerDelta <= 4 &&
                        style.appearance === 'none' &&
                        parseFloat(style.borderTopWidth) >= 2;
                    })()
                """.trimIndent(),
            ) {
                result.set(it)
                completed.countDown()
            }
        }

        assertTrue("Rich checklist style probe did not complete", completed.await(5, TimeUnit.SECONDS))
        assertEquals("Checkbox and task text must share a row with a custom control", "true", result.get())
    }

    @Test
    fun richEditorOpenedFromNewNoteMorphKeepsAOneToOneViewportWithoutScrollbars() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                var selectedNote by androidx.compose.runtime.remember { mutableStateOf<Note?>(null) }
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(
                            selectedNote = selectedNote,
                            editorMode = EditorMode.RICH,
                        ),
                        actions = NotesActions(
                            onCreate = { selectedNote = sampleNote("Write here") },
                            onCloseEditor = { selectedNote = null },
                        ),
                    )
                }
            }
        }

        repeat(1) { attempt ->
            compose.onNodeWithContentDescription("New note").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
            }

            val webView = AtomicReference<WebView>()
            compose.runOnIdle {
                webView.set(requireNotNull(findWebView(compose.activity.window.decorView)))
            }
            val zoomAttempted = CountDownLatch(1)
            compose.activity.runOnUiThread {
                webView.get().zoomIn()
                zoomAttempted.countDown()
            }
            assertTrue("Rich editor zoom probe did not run", zoomAttempted.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            val result = AtomicReference<String>()
            val completed = CountDownLatch(1)
            compose.activity.runOnUiThread {
                webView.get().evaluateJavascript(
                    """
                        (() => {
                          const root = document.documentElement;
                          const body = document.body;
                          const editor = document.querySelector('.rich-editor');
                          const scale = window.visualViewport?.scale ?? 1;
                          const horizontalOverflow = Math.max(root.scrollWidth, body.scrollWidth) - root.clientWidth;
                          const verticalOverflow = Math.max(root.scrollHeight, body.scrollHeight) - root.clientHeight;
                          const editorRect = editor?.getBoundingClientRect();
                          const editorOutsideViewport = !editorRect || editorRect.right > window.innerWidth + 1;
                          const valid = scale >= 0.99 && scale <= 1.01 &&
                            horizontalOverflow <= 1 && verticalOverflow <= 1 && !editorOutsideViewport;
                          return valid ? 'ok' : JSON.stringify({
                            scale,
                            horizontalOverflow,
                            verticalOverflow,
                            editorRight: editorRect?.right,
                            innerWidth: window.innerWidth,
                          });
                        })()
                    """.trimIndent(),
                ) {
                    result.set(it)
                    completed.countDown()
                }
            }

            assertTrue("Rich editor viewport probe $attempt did not complete", completed.await(5, TimeUnit.SECONDS))
            assertEquals(
                "Opening Rich Mode from the New note morph must not zoom or create page scrollbars (attempt $attempt)",
                "\"ok\"",
                result.get(),
            )

            compose.onNodeWithContentDescription("Back to Notes").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("New note").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun longPressingAnInlineImageCanMoveItsLine() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(
                            selectedNote = sampleNote("Before\n\n![Map](picture.png)\n\nAfter"),
                            editorMode = EditorMode.RICH,
                        ),
                        actions = NotesActions(),
                    )
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
        }

        val webView = AtomicReference<WebView>()
        compose.runOnIdle {
            webView.set(requireNotNull(findWebView(compose.activity.window.decorView)))
        }
        evaluateJavascript(
            webView.get(),
            """
                (() => {
                  const image = document.querySelector('img.inline-image');
                  if (!image) return false;
                  const rect = image.getBoundingClientRect();
                  image.dispatchEvent(new PointerEvent('pointerdown', {
                    bubbles: true,
                    pointerId: 7,
                    pointerType: 'touch',
                    isPrimary: true,
                    button: 0,
                    clientX: rect.left + rect.width / 2,
                    clientY: rect.top + rect.height / 2,
                  }));
                  return true;
                })()
            """.trimIndent(),
        )
        Thread.sleep(550)
        assertEquals(
            "The image should enter its lifted state after the long press",
            "true",
            evaluateJavascript(
                webView.get(),
                "document.querySelector('img.inline-image')?.classList.contains('inline-image--dragging') ?? false",
            ),
        )
        evaluateJavascript(
            webView.get(),
            """
                (() => {
                  const image = document.querySelector('img.inline-image');
                  const paragraphs = Array.from(document.querySelectorAll('.rich-editor > p'));
                  const after = paragraphs.find(element => element.textContent?.includes('After'));
                  if (!image || !after) return false;
                  const rect = after.getBoundingClientRect();
                  const event = {
                    bubbles: true,
                    pointerId: 7,
                    pointerType: 'touch',
                    isPrimary: true,
                    button: 0,
                    clientX: rect.left + 8,
                    clientY: rect.top + rect.height / 2 + 1,
                  };
                  image.dispatchEvent(new PointerEvent('pointermove', event));
                  image.dispatchEvent(new PointerEvent('pointerup', event));
                  return true;
                })()
            """.trimIndent(),
        )
        Thread.sleep(250)

        assertEquals(
            "Long-press dragging an image should move its block after the target line",
            "true",
            evaluateJavascript(
                webView.get(),
                """
                    (() => {
                      const children = Array.from(document.querySelector('.rich-editor')?.children ?? []);
                      const imageIndex = children.findIndex(element => element.matches('img.inline-image') || element.querySelector('img.inline-image'));
                      const afterIndex = children.findIndex(element => element.textContent?.includes('After'));
                      return imageIndex > afterIndex;
                    })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun richListsUseCompactIndentation() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(
                            selectedNote = sampleNote("- Bullet\n\n1. Numbered"),
                            editorMode = EditorMode.RICH,
                        ),
                        actions = NotesActions(),
                    )
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("rich-editor-ready").fetchSemanticsNodes().isNotEmpty()
        }
        val webView = AtomicReference<WebView>()
        compose.runOnIdle {
            webView.set(requireNotNull(findWebView(compose.activity.window.decorView)))
        }

        assertEquals(
            "Bullet and numbered lists should each use at most 24 CSS pixels of indentation",
            "true",
            evaluateJavascript(
                webView.get(),
                """
                    (() => {
                      const bullet = document.querySelector('.rich-editor > ul:not([data-type="taskList"])');
                      const numbered = document.querySelector('.rich-editor > ol');
                      if (!bullet || !numbered) return false;
                      return parseFloat(getComputedStyle(bullet).paddingLeft) <= 24 &&
                        parseFloat(getComputedStyle(numbered).paddingLeft) <= 24;
                    })()
                """.trimIndent(),
            ),
        )
    }

    private fun showLibrary() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(state = NotesUiState(), actions = NotesActions())
                }
            }
        }
    }

    private fun sampleNote(markdown: String) = Note(
        id = NoteId("checklist-regression"),
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        title = "Checklist",
        markdown = markdown,
        category = "",
        favorite = false,
        state = NoteState.ACTIVE,
        createdAt = Instant.parse("2026-08-22T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-22T09:00:00Z"),
    )

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun evaluateJavascript(webView: WebView, script: String): String {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        compose.activity.runOnUiThread {
            webView.evaluateJavascript(script) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("Rich editor JavaScript probe did not complete", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
