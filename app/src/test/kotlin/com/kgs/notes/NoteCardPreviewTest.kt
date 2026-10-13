package com.kgs.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.kgs.notes.editor.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCardPreviewTest {
    @Test
    fun richPreviewFormatsMarkdownWhileSourcePreviewKeepsItLiteral() {
        val source = "**bold** and [link](target.md)"

        val rich = noteCardPreview(source, EditorMode.RICH, Color.Blue)
        val raw = noteCardPreview(source, EditorMode.SOURCE, Color.Blue)

        assertEquals("bold and link", rich.text)
        assertEquals(FontWeight.Bold, rich.spanStyles.first().item.fontWeight)
        assertEquals(source, raw.text)
    }
}
