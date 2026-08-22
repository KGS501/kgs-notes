package com.kgs.notes.editor

sealed interface EditorSafety {
    data object RichMode : EditorSafety

    data class SourceMode(val reason: String) : EditorSafety
}

enum class MarkdownCommand {
    BOLD,
    ITALIC,
    HEADING_2,
    BULLET_LIST,
    NUMBERED_LIST,
    TASK_LIST,
    BLOCKQUOTE,
    CODE_BLOCK,
    TABLE,
    UNDO,
    REDO,
}

data class MarkdownSelection(
    val start: Int,
    val end: Int,
)

data class MarkdownEdit(
    val markdown: String,
    val selection: MarkdownSelection,
)

interface MarkdownEditor {
    fun safetyFor(markdown: String): EditorSafety

    fun applyCommand(
        markdown: String,
        selection: MarkdownSelection,
        command: MarkdownCommand,
    ): MarkdownEdit
}

class DefaultMarkdownEditor : MarkdownEditor {
    override fun safetyFor(markdown: String): EditorSafety {
        val checks = listOf(
            Regex("<!--") to "Markdown comments are not safe in Rich Mode yet",
            Regex("(?m)^\\s*\\[\\^[^]]+]:") to "Footnotes are not safe in Rich Mode yet",
            Regex("(?m)^\\s*\\$\\$\\s*$") to "Math blocks are not safe in Rich Mode yet",
            Regex("(?i)```\\s*mermaid") to "Mermaid diagrams are not safe in Rich Mode yet",
            Regex("(?m)^\\s*:::+") to "Markdown directives are not safe in Rich Mode yet",
            Regex("(?s)<[A-Za-z][^>]*>") to "Raw HTML is not safe in Rich Mode yet",
        )
        val unsafe = checks.firstOrNull { (pattern, _) -> pattern.containsMatchIn(markdown) }
        return if (unsafe == null) EditorSafety.RichMode else EditorSafety.SourceMode(unsafe.second)
    }

    override fun applyCommand(
        markdown: String,
        selection: MarkdownSelection,
        command: MarkdownCommand,
    ): MarkdownEdit {
        val normalized = selection.normalized(markdown.length)
        return when (command) {
            MarkdownCommand.BOLD -> markdown.wrap(normalized, "**", "**")
            MarkdownCommand.ITALIC -> markdown.wrap(normalized, "_", "_")
            MarkdownCommand.HEADING_2 -> markdown.replaceLineMarker(normalized, "## ")
            MarkdownCommand.BULLET_LIST -> markdown.replaceLineMarker(normalized, "- ")
            MarkdownCommand.NUMBERED_LIST -> markdown.replaceLineMarker(normalized, "1. ")
            MarkdownCommand.TASK_LIST -> markdown.replaceLineMarker(normalized, "- [ ] ")
            MarkdownCommand.BLOCKQUOTE -> markdown.replaceLineMarker(normalized, "> ")
            MarkdownCommand.CODE_BLOCK -> markdown.wrap(normalized, "```\n", "\n```")
            MarkdownCommand.TABLE -> markdown.insert(
                normalized,
                "| Column | Column |\n| --- | --- |\n| Value | Value |",
            )
            MarkdownCommand.UNDO,
            MarkdownCommand.REDO,
            -> MarkdownEdit(markdown, normalized)
        }
    }

    private fun MarkdownSelection.normalized(length: Int): MarkdownSelection {
        val first = minOf(start, end).coerceIn(0, length)
        val last = maxOf(start, end).coerceIn(first, length)
        return MarkdownSelection(first, last)
    }

    private fun String.wrap(
        selection: MarkdownSelection,
        prefix: String,
        suffix: String,
    ): MarkdownEdit {
        val selected = substring(selection.start, selection.end)
        val updated = replaceRange(selection.start, selection.end, "$prefix$selected$suffix")
        val contentStart = selection.start + prefix.length
        return MarkdownEdit(
            markdown = updated,
            selection = if (selection.start == selection.end) {
                MarkdownSelection(contentStart, contentStart)
            } else {
                MarkdownSelection(contentStart, contentStart + selected.length)
            },
        )
    }

    private fun String.insert(selection: MarkdownSelection, value: String): MarkdownEdit {
        val updated = replaceRange(selection.start, selection.end, value)
        val cursor = selection.start + value.length
        return MarkdownEdit(updated, MarkdownSelection(cursor, cursor))
    }

    private fun String.replaceLineMarker(
        selection: MarkdownSelection,
        marker: String,
    ): MarkdownEdit {
        val lineStart = lastIndexOf('\n', startIndex = (selection.start - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val lineEnd = indexOf('\n', startIndex = selection.start).let { if (it < 0) length else it }
        val line = substring(lineStart, lineEnd)
        val match = LINE_MARKER.find(line)
        val indentation = match?.groupValues?.get(1).orEmpty()
        val oldMarkerLength = match?.value?.length?.minus(indentation.length) ?: 0
        val content = if (match == null) line.drop(indentation.length) else line.drop(match.value.length)
        val replacement = indentation + marker + content
        val updated = replaceRange(lineStart, lineEnd, replacement)
        val delta = marker.length - oldMarkerLength

        fun shift(position: Int): Int = when {
            position <= lineStart + indentation.length -> position
            else -> (position + delta).coerceIn(lineStart + indentation.length + marker.length, updated.length)
        }

        return MarkdownEdit(
            markdown = updated,
            selection = MarkdownSelection(shift(selection.start), shift(selection.end)),
        )
    }

    private companion object {
        val LINE_MARKER = Regex("^(\\s*)(?:-\\s+\\[[ xX]]\\s+|(?:[-+*]|\\d+[.)])\\s+)")
    }
}
