package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiTabMemoryPolicyTest {
    private fun entry(id: Int, live: Boolean = true, protected: Boolean = false) =
        PhormiTabMemoryPolicy.Entry(id, live, id.toLong(), protected)
    @Test fun probesOldestFirstAndSkipsProtectedAndDormant() {
        assertEquals(listOf(2, 4), PhormiTabMemoryPolicy.candidates(
            listOf(entry(4), entry(1, protected = true), entry(3, live = false), entry(2)), 2))
    }
    @Test fun noWorkWhenAlreadyWithinBudget() {
        assertTrue(PhormiTabMemoryPolicy.candidates(listOf(entry(1), entry(2)), 2).isEmpty())
    }
    @Test fun protectedPagesCanExceedBudgetWithoutBeingKilled() {
        assertTrue(PhormiTabMemoryPolicy.candidates((1..8).map { entry(it, protected = true) }, 2).isEmpty())
    }
    @Test fun malformedBudgetNeverRequestsLastLivePage() {
        assertTrue(PhormiTabMemoryPolicy.candidates(listOf(entry(1)), -1).isEmpty())
    }
}
