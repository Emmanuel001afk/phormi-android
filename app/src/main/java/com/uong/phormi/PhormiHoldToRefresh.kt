package com.uong.phormi

/**
 * Two-finger hold-to-refresh.
 *
 * Pull-to-refresh is off by default because a moving finger and a scrolling page look
 * the same to the framework, so an ordinary up/down swipe could reload the page. This
 * gesture has no such ambiguity: the page is never dragged anywhere, the pointer count
 * selects the gesture, and it only fires after the fingers have stayed still for a
 * deliberate moment.
 *
 * Rules:
 * - exactly two pointers, held for at least [MIN_HOLD_MILLIS] (about one second, inside
 *   the 1-3 s window the user asked for);
 * - neither pointer drifts more than [MAX_DRIFT_DP]: a pinch or a two-finger scroll is
 *   movement, not a hold, so it never reloads;
 * - one refresh per gesture; the fingers have to lift before it can fire again.
 *
 * This class only decides *when*. It never touches the WebView and never reads a
 * MotionEvent, so it is testable on a plain JVM and the caller decides what a trigger
 * means.
 */
class PhormiHoldToRefresh(private val minHoldMillis: Long = MIN_HOLD_MILLIS) {
    companion object {
        /** A deliberate hold, not a tap: about one second. */
        const val MIN_HOLD_MILLIS = 1000L

        /** Comfortably larger than a finger's natural jitter, in dp. */
        const val MAX_DRIFT_DP = 12f
    }

    private var startAt = 0L
    private var fired = false

    /** True between [begin] and [cancel]: a two-finger hold is in progress. */
    var tracking = false
        private set

    /** Start measuring a hold; called when the second finger goes down. */
    fun begin(now: Long) {
        if (tracking && !fired) return
        tracking = true
        fired = false
        startAt = now
    }

    /**
     * @param driftDp largest distance any pointer has travelled since [begin]
     * @return true exactly once per hold, at the first call where the hold completes
     */
    fun check(now: Long, driftDp: Float): Boolean {
        if (!tracking || fired) return false
        if (driftDp > MAX_DRIFT_DP) {
            // It turned into a pinch or a two-finger scroll: not this gesture.
            cancel()
            return false
        }
        if (now - startAt < minHoldMillis) return false
        fired = true
        return true
    }

    /** End the gesture: the next hold may fire again. */
    fun cancel() {
        tracking = false
        fired = false
        startAt = 0L
    }
}
