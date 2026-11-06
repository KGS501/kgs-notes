package com.kgs.notes.engine

/** A portable Category path; no filesystem or HTTP details escape the engine. */
fun normalizedCategoryPath(value: String): String {
    val parts = value.replace('\\', '/').split('/').map(String::trim).filter(String::isNotEmpty)
    require(parts.isNotEmpty() && parts.none { it == "." || it == ".." || it.any(Char::isISOControl) }) {
        "Enter a valid Category name"
    }
    val normalized = parts.map {
        it.replace(Regex("[\\p{Cc}\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim(' ', '.')
    }
    require(normalized.none(String::isEmpty)) { "Enter a valid Category name" }
    return normalized.joinToString("/")
}

/** Applies a Category rename or removal (empty [target]) to retained Category paths. */
internal fun Set<String>.replacingCategory(category: String, target: String): Set<String> = mapNotNullTo(linkedSetOf()) { path ->
    when {
        path != category && !path.startsWith("$category/") -> path
        target.isEmpty() -> null
        else -> target + path.removePrefix(category)
    }
}

internal suspend fun NotesEngine.replaceCategoryAssignments(sourceId: SourceId, category: String, target: String): Set<NoteId> {
    // Existing server Categories can use names beyond the portable Local Source
    // naming rules. Match their original path exactly before normalizing the target.
    require(category.isNotBlank()) { "Choose a Category" }
    val from = category
    require(target.isEmpty() || !target.startsWith("$from/")) { "A Category cannot be moved into itself" }
    if (from == target) return emptySet()
    val source = sources.value.firstOrNull { it.id == sourceId }
        ?: throw IllegalStateException("Reconnect this Source to manage its Categories")
    require(source.capabilities.categories && source.capabilities.writable) { "This Source cannot change Categories" }
    val snapshot = library.value
    val targets = (snapshot.active + snapshot.trash).filter {
        // Read-only Notes cannot change Category; they keep their assignment.
        it.sourceId == sourceId && !it.readOnly && (it.category == from || it.category.startsWith("$from/"))
    }
    val changed = linkedSetOf<NoteId>()
    for (note in targets) {
        // Deleting a Category removes its subtree assignments. Renaming preserves
        // each descendant suffix, identity, Favorite and canonical content.
        setCategory(note.id, if (target.isEmpty()) "" else target + note.category.removePrefix(from))
        changed += note.id
    }
    return changed
}
