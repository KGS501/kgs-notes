package com.kgs.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWriteUncertaintyTest {
    private val original = "Original text".toByteArray()
    private val attempt = "Edited text that was being written".toByteArray()

    @Test
    fun onlyContentsOurOwnInterruptedWriteCanLeaveAreAccepted() {
        assertTrue(providerHoldsOwnContents(original, original, emptyList()))
        // Truncate-then-throw and partial writes leave a prefix of the attempted bytes.
        assertTrue(providerHoldsOwnContents(ByteArray(0), original, listOf(attempt)))
        assertTrue(providerHoldsOwnContents(attempt.copyOf(9), original, listOf(attempt)))
        assertTrue(providerHoldsOwnContents(attempt, original, listOf(attempt)))
        // Without a recorded interruption an emptied file is someone else's change.
        assertFalse(providerHoldsOwnContents(ByteArray(0), original, emptyList()))
        // Another app's edit is never overwritten.
        assertFalse(providerHoldsOwnContents("Changed elsewhere".toByteArray(), original, listOf(attempt)))
    }
}
