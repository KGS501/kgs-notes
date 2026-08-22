package com.kgs.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditorSafetyTest {
    private val editor: MarkdownEditor = DefaultMarkdownEditor()

    @Test
    fun `unknown source syntax selects byte-preserving Source Mode`() {
        val markdown = """
            # Calculation

            $$
            e^{i\\pi} + 1 = 0
            $$
        """.trimIndent()

        assertEquals(
            EditorSafety.SourceMode("Math blocks are not safe in Rich Mode yet"),
            editor.safetyFor(markdown),
        )
    }

    @Test
    fun `portable GFM remains available in Rich Mode`() {
        val markdown = """
            # Packing list

            - [x] Tent
            - [ ] Lantern

            | Item | Owner |
            | --- | --- |
            | Map | Mira |
        """.trimIndent()

        assertEquals(EditorSafety.RichMode, editor.safetyFor(markdown))
    }

    @Test
    fun `bold command wraps the Source Mode selection without changing its text`() {
        val result = editor.applyCommand(
            markdown = "Pack coffee",
            selection = MarkdownSelection(5, 11),
            command = MarkdownCommand.BOLD,
        )

        assertEquals("Pack **coffee**", result.markdown)
        assertEquals(MarkdownSelection(7, 13), result.selection)
    }

    @Test
    fun `list commands replace mutually exclusive list markers`() {
        val task = editor.applyCommand(
            markdown = "- Bring the blanket",
            selection = MarkdownSelection(5, 5),
            command = MarkdownCommand.TASK_LIST,
        )
        val numbered = editor.applyCommand(
            markdown = task.markdown,
            selection = task.selection,
            command = MarkdownCommand.NUMBERED_LIST,
        )

        assertEquals("- [ ] Bring the blanket", task.markdown)
        assertEquals("1. Bring the blanket", numbered.markdown)
    }
}
