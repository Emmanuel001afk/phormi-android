package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiDownloadResumePolicyTest {
    @Test fun `resume offset follows the bytes on disk instead of stale progress`() {
        assertEquals(64L, PhormiDownloadResumePolicy.resumeOffset(
            persistedProgress = 128L, actualFileLength = 64L, hasPartialFile = true
        ))
        assertEquals(128L, PhormiDownloadResumePolicy.resumeOffset(
            persistedProgress = 64L, actualFileLength = 128L, hasPartialFile = true
        ))
        assertEquals(0L, PhormiDownloadResumePolicy.resumeOffset(
            persistedProgress = 128L, actualFileLength = 128L, hasPartialFile = false
        ))
        assertEquals(0L, PhormiDownloadResumePolicy.resumeOffset(
            persistedProgress = 0L, actualFileLength = -1L, hasPartialFile = true
        ))
    }

    @Test fun `416 clears a real partial once and cannot recurse after the reset`() {
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.CLEAN_RESTART,
            PhormiDownloadResumePolicy.responseAction(
                existingBytes = 128L,
                statusCode = 416,
                rangeStart = null,
                cleanRestartsUsed = 0
            )
        )
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.FAIL,
            PhormiDownloadResumePolicy.responseAction(
                existingBytes = 0L,
                statusCode = 416,
                rangeStart = null,
                cleanRestartsUsed = 1
            )
        )
        assertFalse(PhormiDownloadResumePolicy.mayRestart(PhormiDownloadResumePolicy.MAX_CLEAN_RESTARTS))
    }

    @Test fun `mismatched 206 restarts cleanly once then fails closed`() {
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.CLEAN_RESTART,
            PhormiDownloadResumePolicy.responseAction(64L, 206, 12L, 0)
        )
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.FAIL,
            PhormiDownloadResumePolicy.responseAction(0L, 206, 12L, 1)
        )
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.FAIL,
            PhormiDownloadResumePolicy.responseAction(64L, 206, null, 1)
        )
    }

    @Test fun `matching partial ranges continue and fresh 206 must begin at zero`() {
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.ACCEPT,
            PhormiDownloadResumePolicy.responseAction(64L, 206, 64L, 0)
        )
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.ACCEPT,
            PhormiDownloadResumePolicy.responseAction(0L, 206, 0L, 0)
        )
        assertEquals(
            PhormiDownloadResumePolicy.ResponseAction.FAIL,
            PhormiDownloadResumePolicy.responseAction(0L, 206, null, 0)
        )
    }
}
