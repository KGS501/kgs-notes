package com.kgs.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.kgs.notes.editor.EditorMode

private val imagePattern = Regex("!\\[([^]]*)]\\([^)]*\\)")
private val linkPattern = Regex("\\[([^]]+)]\\([^)]*\\)")
private val headingPattern = Regex("^#{1,6}\\s+")
private val quotePattern = Regex("^>\\s?")
private val checkboxPattern = Regex("^[-*+] \\[([ xX])] ")
private val bulletPattern = Regex("^[-*+]\\s+")

/** A bounded display projection of canonical Markdown; it is never saved as Note content. */
internal fun noteCardPreview(
    markdown: String,
    mode: EditorMode,
    linkColor: Color,
): AnnotatedString {
    if (mode == EditorMode.SOURCE) return AnnotatedString(markdown)
    return buildAnnotatedString {
        markdown.lines().forEachIndexed { index, sourceLine ->
            if (index > 0) append('\n')
            val line = sourceLine.trimStart()
            val heading = headingPattern.find(line)
            val checkbox = checkboxPattern.find(line)
            val display = when {
                heading != null -> line.drop(heading.value.length)
                checkbox != null -> (if (checkbox.groupValues[1].isBlank()) "☐ " else "☑ ") +
                    line.drop(checkbox.value.length)
                bulletPattern.containsMatchIn(line) -> "• " + line.replaceFirst(bulletPattern, "")
                quotePattern.containsMatchIn(line) -> line.replaceFirst(quotePattern, "")
                else -> line
            }
            if (heading != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendMarkdownInline(display, linkColor)
                }
            } else {
                appendMarkdownInline(display, linkColor)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(markdown: String, linkColor: Color) {
    var index = 0
    while (index < markdown.length) {
        val remaining = markdown.substring(index)
        val image = imagePattern.find(remaining)?.takeIf { it.range.first == 0 }
        if (image != null) {
            append("▧ ")
            append(image.groupValues[1].ifBlank { "Image" })
            index += image.value.length
            continue
        }
        val link = linkPattern.find(remaining)?.takeIf { it.range.first == 0 }
        if (link != null) {
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                appendMarkdownInline(link.groupValues[1], linkColor)
            }
            index += link.value.length
            continue
        }
        val delimiter = when {
            remaining.startsWith("**") -> "**"
            remaining.startsWith("__") -> "__"
            remaining.startsWith("~~") -> "~~"
            remaining.startsWith('`') -> "`"
            remaining.startsWith('*') -> "*"
            remaining.startsWith('_') -> "_"
            else -> null
        }
        if (delimiter != null) {
            val closing = markdown.indexOf(delimiter, index + delimiter.length)
            if (closing > index + delimiter.length) {
                val inner = markdown.substring(index + delimiter.length, closing)
                val style = when (delimiter) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "`" -> SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { appendMarkdownInline(inner, linkColor) }
                index = closing + delimiter.length
                continue
            }
        }
        append(markdown[index])
        index++
    }
}
