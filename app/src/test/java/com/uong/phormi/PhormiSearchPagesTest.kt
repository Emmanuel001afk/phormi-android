package com.uong.phormi

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiSearchPagesTest {
    @Test fun `Unicode queries are durable addresses and not transient blank or local renderer URLs`() {
        val query = "ọjọ́ café + rocket 🚀 & research"
        val address = PhormiSearchPages.address(query)
        assertEquals(query, PhormiSearchPages.query(address))
        assertEquals(address, BrowserSessionRules.durableUrl(address, PhormiSearchPages.BASE))
        assertEquals(address, BrowserSessionRules.durableUrl(address, "about:blank"))
        assertEquals("https://result.example/article", BrowserSessionRules.durableUrl(address, "https://result.example/article"))
    }
    @Test fun `generated HTML survives independently of WebView and never leaks to another query`() {
        val context = RuntimeEnvironment.getApplication()
        val address = PhormiSearchPages.address("browser work")
        val html = "<html><title>Search</title><body>Yesterday's results</body></html>"
        PhormiSearchPages.save(context, 4, address, html).get(5, TimeUnit.SECONDS)
        assertEquals(html, PhormiSearchPages.read(context, 4, address))
        assertNull(PhormiSearchPages.read(context, 4, PhormiSearchPages.address("different work")))
    }
    @Test fun `oversized generated pages do not replace a good cached search`() {
        val context = RuntimeEnvironment.getApplication()
        val address = PhormiSearchPages.address("work")
        PhormiSearchPages.save(context, 7, address, "<html>Good result</html>").get(5, TimeUnit.SECONDS)
        PhormiSearchPages.save(context, 7, address, "x".repeat(200_001)).get(5, TimeUnit.SECONDS)
        assertEquals("<html>Good result</html>", PhormiSearchPages.read(context, 7, address))
    }
}
