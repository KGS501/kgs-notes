package com.kgs.notes

import com.kgs.notes.engine.LocalSourceId
import com.kgs.notes.engine.NoteId
import com.kgs.notes.engine.NoteSummary
import com.kgs.notes.engine.NoteSyncState
import com.kgs.notes.engine.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class NoteLinksTest {
    private val cloud = SourceId("cloud")
    private val notes = listOf(
        note("plan", "Plan", "Work"),
        note("plan-home", "Plan", "Home"),
        note("ideas", "Ideas", "Work/Drafts"),
        note("shopping list", "Shopping List", ""),
        note("cloud-only", "Server Note", "Work", cloud),
    )

    private fun note(id: String, title: String, category: String, source: SourceId = LocalSourceId) = NoteSummary(
        NoteId(id), source, NoteSyncState.LOCAL_SOURCE, title, "", category, false, Instant.EPOCH, Instant.EPOCH,
    )

    private fun resolve(href: String, category: String = "Work", source: SourceId = LocalSourceId) =
        resolveNoteLink(href, source, category, notes)

    @Test
    fun `web, mail and phone links open outside the app`() {
        listOf("https://example.org/doc.pdf", "HTTP://example.org", "mailto:me@example.org", "tel:+4912345")
            .forEach { assertEquals(it, NoteLinkTarget.External(it), resolve(it)) }
    }

    @Test
    fun `schemes that could reach local data or code are never opened`() {
        listOf("javascript:alert(1)", "file:///sdcard/Plan.md", "content://media/1", "intent://x#Intent;end", "data:text/html,x", "//evil.example/Plan.md")
            .forEach { assertEquals(it, NoteLinkTarget.Unavailable, resolve(it)) }
    }

    @Test
    fun `same page anchors do nothing`() {
        assertEquals(NoteLinkTarget.None, resolve("#heading"))
        assertEquals(NoteLinkTarget.None, resolve("  "))
    }

    @Test
    fun `relative Note files resolve against the linking Note's Category`() {
        assertEquals(NoteLinkTarget.Note(NoteId("plan")), resolve("Plan.md"))
        assertEquals(NoteLinkTarget.Note(NoteId("ideas")), resolve("Drafts/Ideas.md"))
        assertEquals(NoteLinkTarget.Note(NoteId("ideas")), resolve("./Drafts/Ideas.md#top"))
        assertEquals(NoteLinkTarget.Note(NoteId("plan-home")), resolve("../Home/Plan.md"))
        assertEquals(NoteLinkTarget.Note(NoteId("plan-home")), resolve("/Home/Plan.md", category = "Work/Drafts"))
        assertEquals(NoteLinkTarget.Note(NoteId("shopping list")), resolve("../Shopping%20List.md"))
        assertEquals(NoteLinkTarget.Note(NoteId("plan")), resolve("plan.MD"))
    }

    @Test
    fun `a bare title finds the only Note with that title anywhere`() {
        assertEquals(NoteLinkTarget.Note(NoteId("shopping list")), resolve("Shopping List"))
        assertEquals(NoteLinkTarget.Note(NoteId("cloud-only")), resolve("Server Note.md"))
        // Two Notes are titled Plan; neither is in this Category, so the link is ambiguous.
        assertEquals(NoteLinkTarget.Unavailable, resolve("Plan.md", category = "Elsewhere"))
    }

    @Test
    fun `missing files and paths above the root are unavailable`() {
        assertEquals(NoteLinkTarget.Unavailable, resolve("Report.pdf"))
        assertEquals(NoteLinkTarget.Unavailable, resolve("Drafts/Plan.md"))
        assertEquals(NoteLinkTarget.Unavailable, resolve("../../../Plan.md"))
        assertEquals(NoteLinkTarget.Unavailable, resolve("%E0%A4%A.md"))
    }
}
