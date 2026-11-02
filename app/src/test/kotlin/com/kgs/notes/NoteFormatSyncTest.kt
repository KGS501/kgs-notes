package com.kgs.notes

import com.kgs.notes.engine.SourceId
import com.kgs.notes.nextcloud.NextcloudNotesApi
import com.kgs.notes.nextcloud.NextcloudNotesSource
import com.kgs.notes.nextcloud.NotesCapabilities
import com.kgs.notes.nextcloud.NotesSettings
import com.kgs.notes.nextcloud.RemoteNote
import com.kgs.notes.nextcloud.RemoteNoteDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatSyncTest {
    private class SettingsApi(val gate: CompletableDeferred<Unit>? = null, val fail: Boolean = false) : NextcloudNotesApi {
        val suffixes = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        override suspend fun capabilities() = NotesCapabilities("1.3")
        override suspend fun notes(): List<RemoteNote> = emptyList()
        override suspend fun note(id: Long): RemoteNote = error("unused")
        override suspend fun create(note: RemoteNoteDraft): RemoteNote = error("unused")
        override suspend fun update(id: Long, expectedEtag: String, note: RemoteNoteDraft): RemoteNote = error("unused")
        override suspend fun delete(id: Long) = Unit
        override suspend fun updateSettings(fileSuffix: String): NotesSettings {
            started.complete(Unit)
            gate?.await()
            if (fail) error("server unavailable")
            suffixes += fileSuffix
            return NotesSettings(fileSuffix)
        }
    }

    private fun source(name: String, api: NextcloudNotesApi) = NextcloudNotesSource(name, api, SourceId(name))

    @Test
    fun removingOrAddingSourcesWhileARequestIsSuspendedDoesNotBreakIteration() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = SettingsApi(gate)
        val removed = SettingsApi()
        val sources = linkedMapOf(SourceId("a") to source("a", slow), SourceId("b") to source("b", removed))
        val applying = async { applyNoteFormatToSources(sources.values, ".txt") }
        slow.started.await()
        sources.remove(SourceId("b"))
        sources[SourceId("c")] = source("c", SettingsApi())
        gate.complete(Unit)
        yield()
        assertTrue(applying.await())
        assertEquals(listOf(".txt"), slow.suffixes)
        // The snapshot taken before suspending still covers the Source removed meanwhile.
        assertEquals(listOf(".txt"), removed.suffixes)
    }

    @Test
    fun aFailingSourceIsReportedAfterTheOthersFinish() = runBlocking {
        val healthy = SettingsApi()
        assertFalse(applyNoteFormatToSources(listOf(source("x", SettingsApi(fail = true)), source("y", healthy)), ".md"))
        assertEquals(listOf(".md"), healthy.suffixes)
    }
}
