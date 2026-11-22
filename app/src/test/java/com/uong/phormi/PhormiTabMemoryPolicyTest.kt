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
    @Test fun recentlyUsedTabsKeepTheirPageInsideTheGraceWindow() {
        val now = 10_000_000L
        val entries = listOf(
            PhormiTabMemoryPolicy.Entry(1, true, now - 1_000, false),
            PhormiTabMemoryPolicy.Entry(2, true, now - 2_000, false)
        )
        // Nothing is safe to discard yet: keep the pages rather than blank a tab.
        assertTrue(PhormiTabMemoryPolicy.candidates(entries, 1, now, 60_000).isEmpty())
        // Once the window has passed, the oldest page is the candidate.
        // Sorted oldest-first: entry 2 was used longer ago than entry 1.
        assertEquals(listOf(2, 1), PhormiTabMemoryPolicy.candidates(entries, 1, now + 120_000, 60_000))
    }

    @Test fun `real memory pressure ignores the grace window`() {
        // onTrimMemory must actually free something, or the device kills the process.
        val now = 10_000_000L
        val entries = listOf(
            PhormiTabMemoryPolicy.Entry(1, true, now - 1_000, false),
            PhormiTabMemoryPolicy.Entry(2, true, now - 2_000, false)
        )
        assertEquals(listOf(2, 1),
            PhormiTabMemoryPolicy.candidates(entries, 1, now, 60_000, honourGrace = false))
    }
}
