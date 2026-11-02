package com.kgs.notes

import com.kgs.notes.nextcloud.NextcloudNotesSource
import kotlinx.coroutines.CancellationException

/**
 * Applies the preferred Nextcloud note file suffix. The Sources are copied before the first
 * request suspends, so connecting or removing another Source meanwhile cannot break iteration.
 * Returns false when any Source failed; cancellation is never reported as a failure.
 */
internal suspend fun applyNoteFormatToSources(sources: Collection<NextcloudNotesSource>, suffix: String): Boolean {
    val snapshot = sources.toList()
    var succeeded = true
    for (source in snapshot) {
        try {
            source.setFileSuffix(suffix)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            succeeded = false
        }
    }
    return succeeded
}
