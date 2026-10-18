package com.kgs.notes

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal fun noteInitial(title: String, locale: Locale = Locale.getDefault()): String {
    val text = Normalizer.normalize(title.trim(), Normalizer.Form.NFD).uppercase(locale)
    if (text.isEmpty()) return "#"
    val first = text.codePointAt(0)
    return if (Character.isLetter(first)) String(Character.toChars(first)) else "#"
}

internal fun titleComparator(grouped: Boolean, locale: Locale): Comparator<com.kgs.notes.engine.NoteSummary> {
    val collator = Collator.getInstance(locale)
    val titles = Comparator<com.kgs.notes.engine.NoteSummary> { a, b -> collator.compare(a.title, b.title) }
    return if (grouped) Comparator<com.kgs.notes.engine.NoteSummary> { a, b -> collator.compare(noteInitial(a.title, locale), noteInitial(b.title, locale)) }.then(titles) else titles
}
