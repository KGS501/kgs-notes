package com.kgs.notes

import com.kgs.notes.engine.AttachmentId
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedAttachmentTargetsTest {
    private val id = AttachmentId("a1")
    private val deep = "../../.kgs-notes-attachments/n1/a1.png"
    private val shallow = "../.kgs-notes-attachments/n1/a1.png"

    @Test
    fun rebasesADraftMovedToAShallowerCategoryKeepingNewText() {
        val draft = "Typed before the move\n\n![Forest]($deep)\n\nTyped during the move"
        val rebased = rebaseManagedTargets(draft, mapOf(id to deep), mapOf(id to shallow))
        assertEquals("Typed before the move\n\n![Forest]($shallow)\n\nTyped during the move", rebased)
    }

    @Test
    fun rebasesToADeeperCategoryWithoutRewritingTargetsTwice() {
        val draft = "![A]($shallow) and <$shallow>"
        assertEquals("![A]($deep) and <$deep>", rebaseManagedTargets(draft, mapOf(id to shallow), mapOf(id to deep)))
        // A target that merely ends with the old one is a different path.
        val longer = "![B](../$shallow)"
        assertEquals(longer, rebaseManagedTargets(longer, mapOf(id to shallow), mapOf(id to deep)))
    }

    @Test
    fun leavesUnknownOrUnchangedTargetsAlone() {
        val draft = "![A]($deep)"
        assertEquals(draft, rebaseManagedTargets(draft, mapOf(id to deep), emptyMap()))
        assertEquals(draft, rebaseManagedTargets(draft, mapOf(id to deep), mapOf(id to deep)))
    }
}
