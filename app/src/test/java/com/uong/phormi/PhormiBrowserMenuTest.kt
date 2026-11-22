package com.uong.phormi

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Source-level guarantee for the reported "I can't reach the menu from the tab page"
 * problem: every tab control the browser owns is reachable from the menu.
 */
class PhormiBrowserMenuTest {
    /** Gradle runs JVM tests from the module directory; walk up if it changes. */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        fail("cannot locate $relative")
        return ""
    }

    @Test fun `menu exposes tab overview, new tab and close tab`() {
        val menu = source("src/main/java/com/uong/phormi/MenuActivity.kt")
        for (id in listOf("menu_tabs", "menu_new_tab", "menu_close_tab", "menu_home", "menu_data_saver")) {
            assertTrue("menu must expose $id", "R.id.$id" in menu)
        }
    }

    @Test fun `browser handles every menu tab action`() {
        val main = source("src/main/java/com/uong/phormi/MainActivity.kt")
        for (action in listOf("ACTION_CLOSE_TAB", "ACTION_NEW_TAB", "ACTION_HOME", "ACTION_TABS_RESULT", "ACTION_DATA_SAVER")) {
            assertTrue("browser must handle $action", action in main)
        }
    }

    @Test fun `tab count opens the overview`() {
        val main = source("src/main/java/com/uong/phormi/MainActivity.kt")
        val marker = "findViewById<TextView>(R.id.tab_count).setOnClickListener"
        val index = main.indexOf(marker)
        assertTrue("tab count must be clickable", index >= 0)
        assertTrue("the tab counter opens the tab overview",
            "TabsOverviewActivity" in main.substring(index, (index + 400).coerceAtMost(main.length)))
    }

    @Test fun `menu layout and activity agree on ids`() {
        val layout = source("src/main/res/layout/activity_menu.xml")
        val menu = source("src/main/java/com/uong/phormi/MenuActivity.kt")
        val wired = Regex("""R\.id\.(menu_\w+)""").findAll(menu).map { it.groupValues[1] }.toSet()
        val declared = Regex("""@\+id/(menu_\w+)""").findAll(layout).map { it.groupValues[1] }.toSet()
        assertEquals("menu ids wired in code but missing from the layout", emptySet<String>(), wired - declared)
    }
}
