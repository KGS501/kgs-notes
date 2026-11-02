package com.kgs.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.kgs.notes.design.R.string as UiR
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.SourceId

/** What tapping a link in a Note does. */
sealed interface NoteLinkTarget {
    /** Another Note in the library, opened inside the app. */
    data class Note(val id: NoteId) : NoteLinkTarget
    /** A web page, mail address or similar, opened by another app. */
    data class External(val uri: String) : NoteLinkTarget
    /** A same-page anchor: nothing to open. */
    data object None : NoteLinkTarget
    /** A file or Note that isn't in the library, or a scheme that is never opened. */
    data object Unavailable : NoteLinkTarget
}

private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")
private val NOTE_EXTENSIONS = listOf(".md", ".markdown", ".txt")

/**
 * Resolves [href] from a Note in [fromCategory] of [fromSource]. Relative links name Notes the way
 * Nextcloud stores them, as `Category/Title.md` files: `Other.md`, `../Work/Plan.md` or `/Work/Plan.md`.
 */
fun resolveNoteLink(href: String, fromSource: SourceId, fromCategory: String, notes: List<NoteSummary>): NoteLinkTarget {
    val link = href.trim()
    if (link.isEmpty() || link.startsWith("#")) return NoteLinkTarget.None
    SCHEME.find(link)?.let { match ->
        return if (match.groupValues[1].lowercase() in EXTERNAL_SCHEMES) NoteLinkTarget.External(link) else NoteLinkTarget.Unavailable
    }
    if (link.startsWith("//")) return NoteLinkTarget.Unavailable
    val path = percentDecode(link.substringBefore('#').substringBefore('?')) ?: return NoteLinkTarget.Unavailable
    val segments = if (path.startsWith("/")) mutableListOf()
        else fromCategory.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (segment in path.split('/')) when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isEmpty()) return NoteLinkTarget.Unavailable else segments.removeAt(segments.lastIndex)
        else -> segments += segment
    }
    val file = segments.removeLastOrNull() ?: return NoteLinkTarget.Unavailable
    val title = NOTE_EXTENSIONS.firstOrNull { file.endsWith(it, ignoreCase = true) }?.let { file.dropLast(it.length) } ?: file
    val category = segments.joinToString("/")
    val explicitFolder = path.contains('/')

    fun List<NoteSummary>.unique(): NoteId? = singleOrNull()?.id
    fun find(candidates: List<NoteSummary>): NoteId? =
        candidates.filter { it.category == category && it.title == title }.unique()
            ?: candidates.filter { it.category.equals(category, ignoreCase = true) && it.title.equals(title, ignoreCase = true) }.unique()
            // A bare name may also mean a Note elsewhere in the library, if only one has it.
            ?: if (explicitFolder) null else candidates.filter { it.title.equals(title, ignoreCase = true) }.unique()

    return (find(notes.filter { it.sourceId == fromSource }) ?: find(notes))
        ?.let(NoteLinkTarget::Note) ?: NoteLinkTarget.Unavailable
}

private fun percentDecode(value: String): String? = runCatching {
    java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
}.getOrNull()

/** Opens [target] outside the app, or explains why it can't be opened. */
fun Context.openLinkTarget(target: NoteLinkTarget) {
    when (target) {
        is NoteLinkTarget.External -> runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).addCategory(Intent.CATEGORY_BROWSABLE))
        }.onFailure { Toast.makeText(this, getString(UiR.ui_no_app_can_open_this_link), Toast.LENGTH_SHORT).show() }
        NoteLinkTarget.Unavailable -> Toast.makeText(this,
            getString(UiR.ui_this_link_points_to_a_file_or_note_that_isn_t_in_kgs_notes), Toast.LENGTH_SHORT).show()
        is NoteLinkTarget.Note, NoteLinkTarget.None -> Unit
    }
}
