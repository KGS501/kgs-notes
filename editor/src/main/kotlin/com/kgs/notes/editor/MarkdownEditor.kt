package com.kgs.notes.editor

sealed interface EditorSafety {
    data object RichMode : EditorSafety

    data class SourceMode(val reason: String) : EditorSafety
}

interface MarkdownEditor {
    fun safetyFor(markdown: String): EditorSafety
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
}
