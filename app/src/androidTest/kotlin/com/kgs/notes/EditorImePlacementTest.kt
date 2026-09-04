package com.kgs.notes

import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kgs.notes.design.KgsNotesTheme
import com.kgs.notes.editor.EditorMode
import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.Note
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteState
import com.kgs.notes.engine.NoteSyncState
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorImePlacementTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolbarStaysAboveImeWhenTheWindowDoesNotResize() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            activity.setContent {
                KgsNotesTheme {
                    KgsNotesScreen(
                        state = NotesUiState(
                            selectedNote = sampleNote(),
                            editorMode = EditorMode.SOURCE,
                        ),
                        actions = NotesActions(),
                    )
                }
            }
        }

        compose.onNodeWithTag("source-editor").performTouchInput {
            down(center)
            up()
        }.assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("source-editor").performTextInput("x")
        var imeVisible = false
        for (attempt in 0 until 5) {
            compose.runOnIdle {
                val activity = compose.activity
                val focusedView = requireNotNull(activity.currentFocus) {
                    "The Compose editor must own Android window focus before requesting the IME"
                }
                val inputMethodManager = activity.getSystemService(InputMethodManager::class.java)
                WindowInsetsControllerCompat(activity.window, focusedView)
                    .show(WindowInsetsCompat.Type.ime())
                focusedView.post {
                    inputMethodManager.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            val attemptDeadline = android.os.SystemClock.uptimeMillis() + 3_000
            while (!imeVisible && android.os.SystemClock.uptimeMillis() < attemptDeadline) {
                compose.runOnIdle {
                    imeVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (!imeVisible) android.os.SystemClock.sleep(100)
            }
            if (imeVisible) break
        }
        assertTrue("The software keyboard must become visible for this placement test", imeVisible)

        var visibleBottom = Float.MAX_VALUE
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
            visibleBottom = root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.toFloat()
        }
        val toolbarBottom = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            "Toolbar bottom $toolbarBottom must not extend below visible editor bottom $visibleBottom",
            toolbarBottom <= visibleBottom + 1f,
        )

        compose.onNodeWithContentDescription("Lists").performClick()
        compose.waitForIdle()
        val menuCoordinates = compose.onNodeWithTag("list-style-menu")
            .fetchSemanticsNode().layoutInfo.coordinates
        val toolbarCoordinates = compose.onNodeWithTag("editor-toolbar")
            .fetchSemanticsNode().layoutInfo.coordinates
        val menuBottom = menuCoordinates.localToScreen(
            Offset(0f, menuCoordinates.size.height.toFloat()),
        ).y
        val toolbarTop = toolbarCoordinates.localToScreen(Offset.Zero).y
        val density = compose.activity.resources.displayMetrics.density
        val actualGap = toolbarTop - menuBottom
        val maximumGap = 8f * density
        assertTrue(
            "List popup gap $actualGap must stay close to its toolbar anchor",
            actualGap in 0f..maximumGap,
        )
    }

    private fun sampleNote() = Note(
        id = NoteId("ime-note"),
        sourceId = LocalSourceId,
        syncState = NoteSyncState.LOCAL_SOURCE,
        title = "Keyboard test",
        markdown = "A note",
        category = "",
        favorite = false,
        state = NoteState.ACTIVE,
        createdAt = Instant.parse("2026-08-22T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-22T09:00:00Z"),
    )
}
