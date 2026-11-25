package com.uong.phormi

/** Pure decisions for safe HTTP range-resume recovery. */
internal object PhormiDownloadResumePolicy {
    const val MAX_CLEAN_RESTARTS = 1

    enum class ResponseAction { ACCEPT, CLEAN_RESTART, FAIL }

    /** Range requests must match bytes physically present; persisted progress can be ahead of a crash-truncated file. */
    @Suppress("UNUSED_PARAMETER")
    fun resumeOffset(persistedProgress: Long, actualFileLength: Long?, hasPartialFile: Boolean): Long =
        if (!hasPartialFile) 0L else actualFileLength?.coerceAtLeast(0L) ?: 0L

    fun responseAction(
        existingBytes: Long,
        statusCode: Int,
        rangeStart: Long?,
        cleanRestartsUsed: Int
    ): ResponseAction {
        if (statusCode == 416) {
            return if (existingBytes > 0L && mayRestart(cleanRestartsUsed)) {
                ResponseAction.CLEAN_RESTART
            } else {
                ResponseAction.FAIL
            }
        }
        if (statusCode == 206 && rangeStart != existingBytes) {
            return if (existingBytes > 0L && mayRestart(cleanRestartsUsed)) {
                ResponseAction.CLEAN_RESTART
            } else {
                ResponseAction.FAIL
            }
        }
        return ResponseAction.ACCEPT
    }

    fun mayRestart(restartsAlreadyUsed: Int): Boolean =
        restartsAlreadyUsed < MAX_CLEAN_RESTARTS
}
