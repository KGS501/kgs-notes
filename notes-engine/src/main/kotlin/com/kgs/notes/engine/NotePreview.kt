package com.kgs.notes.engine

/** Keep library previews small; the full Note remains authoritative in its Source. */
internal fun notePreviewMarkdown(markdown: String, title: String): String {
    val lines = markdown.lineSequence().dropWhile(String::isBlank)
    val first = lines.firstOrNull().orEmpty().trim().replace(Regex("^#{1,6}\\s+"), "")
    val body = if (first == title.trim()) lines.drop(1) else lines
    return body.dropWhile(String::isBlank).take(32).joinToString("\n").take(4096)
}
