package com.uong.phormi

/** Exponential local lockout after repeated failed PIN guesses. */
internal object PhormiPinAttemptPolicy {
    const val MAX_UNTHROTTLED_FAILURES = 4
    const val BASE_LOCKOUT_MS = 30_000L
    const val MAX_LOCKOUT_MS = 5 * 60_000L

    data class State(val failures: Int = 0, val lockedUntilMs: Long = 0L)

    fun remainingMs(state: State, nowMs: Long): Long =
        (state.lockedUntilMs - nowMs).coerceAtLeast(0L)

    fun afterFailure(state: State, nowMs: Long): State {
        val failures = (state.failures + 1).coerceAtLeast(1)
        val duration = lockoutDurationMs(failures)
        return State(failures, if (duration == 0L) 0L else nowMs + duration)
    }

    fun afterSuccess(): State = State()

    fun lockoutDurationMs(failures: Int): Long {
        if (failures <= MAX_UNTHROTTLED_FAILURES) return 0L
        val exponent = (failures - MAX_UNTHROTTLED_FAILURES - 1).coerceAtMost(20)
        val multiplier = 1L shl exponent
        return (BASE_LOCKOUT_MS * multiplier).coerceAtMost(MAX_LOCKOUT_MS)
    }
}
