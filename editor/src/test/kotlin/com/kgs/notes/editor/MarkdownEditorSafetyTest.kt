package com.kgs.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditorSafetyTest {
    private val editor: MarkdownEditor = DefaultMarkdownEditor()

    @Test
    fun `CommonMark angle autolinks are not raw HTML`() {
        val markdown = """
            # Reference

            <https://example.org/reference>
            <reader@example.org>
            <mailto:reader@example.org>

            | Reference | Description |
            | --- | --- |
            | <https://example.org/specification> | **Specification** |
        """.trimIndent()
        assertEquals(EditorSafety.RichMode, editor.safetyFor(markdown))
    }

    @Test
    fun `code and escaped syntax never trigger the unsupported markup warning`() {
        val examples = listOf(
            "Example `<section>text</section>` and `<!-- comment -->`.",
            "```html\n<section>text</section>\n<!-- comment -->\n```",
            "~~~text\n$$\n[^one]: text\n:::warning\n~~~",
            "    <section>text</section>\n    <!-- comment -->",
            "Literal \\<section> and &lt;section&gt;.",
            "```mermaid\ngraph TD; A-->B\n```",
        )
        examples.forEachIndexed { index, markdown ->
            assertEquals("Code example $index", EditorSafety.RichMode, editor.safetyFor(markdown))
        }
    }

    @Test
    fun `unmatched code delimiters still allow preserved HTML fragments`() {
        assertEquals(EditorSafety.RichMode,
            editor.safetyFor("Unmatched ` then <section>text</section>"))
    }

    @Test
    fun `extension syntax stays in Rich Mode with byte-preserving Source Capsules`() {
        val markdown = """
            # Calculation

            $$
            e^{i\\pi} + 1 = 0
            $$
        """.trimIndent()

        assertEquals(
            EditorSafety.RichMode,
            editor.safetyFor(markdown),
        )
    }

    @Test
    fun `HTML comments metadata and footnotes do not force the whole Note to Source Mode`() {
        listOf("<!-- comment -->\n\n# Title", "<details><summary>Details</summary>Content</details>",
            "[^one]: Footnote", ":::warning\nContent\n:::", "---\ntitle: Sample\n---")
            .forEach { assertEquals(EditorSafety.RichMode, editor.safetyFor(it)) }
    }

    @Test
    fun `oversized Notes and nonportable null characters retain Source Mode protection`() {
        assertEquals(EditorSafety.RichMode, editor.safetyFor("x".repeat(200_000)))
        assertEquals(EditorSafety.SourceMode("This Note is large. Source Mode keeps editing responsive."),
            editor.safetyFor("x".repeat(200_001)))
        assertEquals(EditorSafety.SourceMode("This Note contains characters that need Source Mode to stay intact."),
            editor.safetyFor("Before\u0000after"))
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

    @Test
    fun `an Inline Image inserts portable Markdown and preserves a readable label`() {
        val result = editor.insertAttachment(
            markdown = "# Ridge walk\n\n",
            selection = MarkdownSelection(14, 14),
            attachment = MarkdownAttachment(
                displayName = "ridge [east].jpg",
                target = "../../.kgs-notes-attachments/note-id/file.jpg",
                inlineImage = true,
            ),
        )

        assertEquals(
            "# Ridge walk\n\n![ridge \\[east\\].jpg](../../.kgs-notes-attachments/note-id/file.jpg)",
            result.markdown,
        )
        assertEquals(MarkdownSelection(82, 82), result.selection)
    }

    @Test
    fun `an Inline Image inserted after text starts in its own Markdown block`() {
        val result = editor.insertAttachment(
            markdown = "A quiet ridge",
            selection = MarkdownSelection(13, 13),
            attachment = MarkdownAttachment(
                displayName = "view.jpg",
                target = ".kgs-notes-attachments/note-id/file.jpg",
                inlineImage = true,
            ),
        )

        assertEquals(
            "A quiet ridge\n\n![view.jpg](.kgs-notes-attachments/note-id/file.jpg)",
            result.markdown,
        )
    }
}
