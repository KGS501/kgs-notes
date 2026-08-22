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
}
