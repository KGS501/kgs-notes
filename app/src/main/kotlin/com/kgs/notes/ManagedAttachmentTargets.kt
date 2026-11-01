package com.kgs.notes

import com.kgs.notes.engine.AttachmentId

/**
 * Moving a Note between Category depths changes the relative path of its managed
 * attachments. A draft written before the move keeps the old targets; rewrite only whole
 * targets, so a deeper target that ends with a shallower one is never rewritten twice.
 */
internal fun rebaseManagedTargets(
    markdown: String,
    before: Map<AttachmentId, String>,
    after: Map<AttachmentId, String>,
): String = before.entries.fold(markdown) { content, (id, oldTarget) ->
    val newTarget = after[id]
    if (newTarget == null || newTarget == oldTarget || oldTarget.isEmpty()) content
    else Regex("(?<![\\w./%-])" + Regex.escape(oldTarget) + "(?![\\w./%-])")
        .replace(content) { Regex.escapeReplacement(newTarget) }
}
