package com.kgs.notes

import java.text.Normalizer
import java.util.Locale

/**
 * Library search compares Unicode-compatible, accent-insensitive, case-folded text.
 * Every whitespace-separated term must occur in the title, Category, or full Markdown body.
 */
internal fun normalizeForSearch(text: String): String = Normalizer
    .normalize(text, Normalizer.Form.NFKD)
    .replace(CombiningMarks, "")
    .lowercase(Locale.ROOT)

internal fun searchTerms(query: String): List<String> = normalizeForSearch(query)
    .split(SearchWhitespace)
    .filter(String::isNotEmpty)

/** [normalizedFields] must already be passed through [normalizeForSearch]. */
internal fun matchesSearch(terms: List<String>, normalizedFields: List<String>): Boolean =
    terms.all { term -> normalizedFields.any { it.contains(term) } }

private val CombiningMarks = Regex("\\p{Mn}+")
private val SearchWhitespace = Regex("\\s+")
