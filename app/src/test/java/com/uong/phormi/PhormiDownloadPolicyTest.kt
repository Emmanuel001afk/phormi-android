package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test

class PhormiDownloadPolicyTest {
    @Test fun `resume appends only an exact validated range`() {
        val plan = PhormiDownloadPolicy.plan(100, 206, "bytes 100-199/200", 100, -1)
        assertTrue(plan.append); assertFalse(plan.restart); assertEquals(200L, plan.total)
    }
    @Test fun `range rejection and wrong starting bytes trigger a bounded restart`() {
        assertTrue(PhormiDownloadPolicy.plan(100, 416, "bytes */80", 0, 80).restart)
        assertTrue(PhormiDownloadPolicy.plan(100, 206, "bytes 0-99/200", 100, 200).restart)
        assertTrue(PhormiDownloadPolicy.plan(100, 206, null, 100, 200).restart)
        assertTrue(PhormiDownloadPolicy.plan(0, 206, "bytes 50-99/100", 50, 100).restart)
    }
    @Test fun `server ignoring Range replaces rather than appends the whole file`() {
        val plan = PhormiDownloadPolicy.plan(100, 200, null, 200, 300)
        assertFalse(plan.append); assertFalse(plan.restart); assertEquals(200L, plan.total)
    }
    @Test fun `chunked responses retain an honest unknown length`() {
        assertEquals(-1L, PhormiDownloadPolicy.plan(0, 200, null, -1, -1).total)
    }
    @Test fun `only identical scheme host and effective port share credentials`() {
        assertEquals(PhormiDownloadPolicy.origin("https://example.com/path"), PhormiDownloadPolicy.origin("https://EXAMPLE.com:443/file"))
        assertNotEquals(PhormiDownloadPolicy.origin("https://example.com"), PhormiDownloadPolicy.origin("https://cdn.example.com"))
        assertNotEquals(PhormiDownloadPolicy.origin("https://example.com"), PhormiDownloadPolicy.origin("http://example.com"))
    }
    @Test fun `stream playlists are distinct from directly downloadable media`() {
        assertTrue(PhormiDownloadPolicy.isStream("https://cdn.example/video.m3u8?token=x", null))
        assertTrue(PhormiDownloadPolicy.isStream("https://cdn.example/watch", "application/dash+xml"))
        assertFalse(PhormiDownloadPolicy.isStream("https://cdn.example/video.mp4", "video/mp4"))
    }
}
