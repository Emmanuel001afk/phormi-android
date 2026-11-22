package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

/**
 * The refresh gesture must be deliberate. These tests pin the two things the user
 * reported: an ordinary scroll never refreshes, and a still two-finger hold does.
 */
class PhormiHoldToRefreshTest {

    @Test fun `a still two-finger hold of one second refreshes exactly once`() {
        val gesture = PhormiHoldToRefresh()
        gesture.begin(1_000L)
        assertFalse("must not fire before the hold completes", gesture.check(1_400L, 0f))
        assertTrue("the hold completed", gesture.check(2_000L, 0f))
        // Holding longer must not reload again and again.
        assertFalse(gesture.check(3_000L, 0f))
        assertFalse(gesture.check(5_000L, 0f))
    }

    @Test fun `a pinch or two-finger scroll is movement, not a hold`() {
        val gesture = PhormiHoldToRefresh()
        gesture.begin(0L)
        assertFalse("a moving gesture never triggers", gesture.check(2_000L, 40f))
        assertFalse(gesture.check(3_000L, 0f))
        assertFalse(gesture.tracking)
    }

    @Test fun `lifting the fingers re-arms the gesture`() {
        val gesture = PhormiHoldToRefresh()
        gesture.begin(0L)
        assertTrue(gesture.check(1_000L, 0f))
        gesture.cancel()
        gesture.begin(5_000L)
        assertTrue("a second deliberate hold works too", gesture.check(6_000L, 0f))
    }

    @Test fun `a quick two-finger tap does nothing`() {
        val gesture = PhormiHoldToRefresh()
        gesture.begin(0L)
        assertFalse(gesture.check(250L, 0f))
        gesture.cancel()
        assertFalse(gesture.check(300L, 0f))
    }

    @Test fun `small finger jitter still counts as still`() {
        val gesture = PhormiHoldToRefresh()
        gesture.begin(0L)
        assertTrue(gesture.check(1_000L, PhormiHoldToRefresh.MAX_DRIFT_DP - 0.5f))
    }
}
