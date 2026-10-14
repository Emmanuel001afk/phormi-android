package com.uong.phormi

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiAiProtocolTest {
    @Test fun `tunnel and common provider response shapes are accepted`() {
        val responses = listOf(
            "{\"ok\":true,\"answer\":\"Hello\"}",
            "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}",
            "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}",
            "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}]}")
        responses.forEach { assertEquals("Hello", PhormiAiProtocol.text(it)) }
    }
    @Test fun `null is not treated as an assistant answer`() {
        assertNull(PhormiAiProtocol.text("{\"choices\":[{\"message\":{\"content\":null}}]}"))
    }
    @Test fun `truncated responses report a useful error instead of partial success`() {
        assertThrows(IOException::class.java) { PhormiAiProtocol.text("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}") }
    }
    @Test fun `actions require a real tab and exact control ID`() {
        val valid = PhormiAiProtocol.action("```json\n{\"action\":\"type\",\"tabId\":2,\"elementId\":\"e1\",\"text\":\"Draft\"}\n```")
        assertEquals("e1", valid.getString("elementId"))
        assertThrows(IOException::class.java) { PhormiAiProtocol.action("{\"action\":\"click\",\"tabId\":2,\"text\":\"\"}") }
        assertThrows(IOException::class.java) { PhormiAiProtocol.action("{\"action\":\"done\",\"tabId\":-1}") }
        assertThrows(IOException::class.java) { PhormiAiProtocol.action("{\"action\":\"navigate\",\"tabId\":2,\"url\":\"javascript:alert(1)\"}") }
    }
    @Test fun `web URLs reject credentials and non-web schemes`() {
        assertTrue(PhormiAiProtocol.httpUrl("https://example.com/path?q=x"))
        assertFalse(PhormiAiProtocol.httpUrl("https://user:password@example.com"))
        assertFalse(PhormiAiProtocol.httpUrl("file:///data/private"))
        assertFalse(PhormiAiProtocol.httpUrl("https://"))
    }
}
