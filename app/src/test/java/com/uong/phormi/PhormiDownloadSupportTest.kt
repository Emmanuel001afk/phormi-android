package com.uong.phormi

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiDownloadSupportTest {
    @Test fun `UTF8 download names retain literal plus signs and infer a real media type`() {
        val info = PhormiDownloadSupport.resolve("https://cdn.example/fetch?id=2", "attachment; filename*=UTF-8''caf%C3%A9+mix.mp4", "application/octet-stream", "Phormi", null, null)
        assertEquals("café+mix.mp4", info.fileName)
        assertEquals("video/mp4", info.mimeType)
        assertEquals("Video", info.category)
    }
    @Test fun `quoted names and query strings do not corrupt the file extension`() {
        val info = PhormiDownloadSupport.resolve("https://cdn.example/music.m4a?token=x.mp4", "attachment; filename=\"My music.m4a\"", null, null, null, null)
        assertEquals("My music.m4a", info.fileName)
        assertEquals("audio/mp4", info.mimeType)
    }
    @Test fun `server filenames cannot traverse paths or inject control characters`() {
        val info = PhormiDownloadSupport.resolve("https://example.com/file", "attachment; filename=\"../../unsafe\u0000.pdf\"", "application/pdf", null, null, null)
        assertFalse(info.fileName.contains('/'))
        assertFalse(info.fileName.contains('\u0000'))
        assertFalse(info.fileName.startsWith('.'))
        assertTrue(info.fileName.endsWith(".pdf"))
    }
    @Test fun `long filenames retain their media extension`() {
        val info = PhormiDownloadSupport.resolve("https://example.com/file", "attachment; filename=\"${"a".repeat(250)}.webm\"", "video/webm", null, null, null)
        assertTrue(info.fileName.length <= 180)
        assertTrue(info.fileName.endsWith(".webm"))
    }
}
