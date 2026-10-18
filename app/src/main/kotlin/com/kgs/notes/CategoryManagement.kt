package com.kgs.notes

import com.kgs.notes.engine.LibrarySnapshot

internal fun managedCategoryList(library: LibrarySnapshot): List<ManagedCategory> {
    val notesBySource = (library.active + library.trash).groupBy { it.sourceId }
    return (notesBySource.keys + library.categories.keys).distinct().flatMap { sourceId ->
        val notes = notesBySource[sourceId].orEmpty()
        // Retained Categories appear even before any Note uses them.
        val paths = (notes.map { it.category } + library.categories[sourceId].orEmpty()).flatMap { category ->
            val segments = category.split('/').filter(String::isNotBlank)
            segments.indices.map { segments.take(it + 1).joinToString("/") }
        }.distinct().sortedBy(String::lowercase)
        paths.map { path -> ManagedCategory(sourceId, path, notes.count { it.category == path || it.category.startsWith("$path/") }) }
    }
}
