package com.aurviz.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T4 (#102): Settings, Schedules, Memory & Browser flagship craft.
// Pure-JVM source guards (Compose is Android-bound); mirrors FlagshipUiCraftTest style.
class FlagshipT4CraftTest {

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }

    private fun screen(name: String): String =
        File(moduleDir(), "src/main/java/com/aurviz/agent/ui/screens/$name").readText()

    @Test
    fun settingsUsesSharedCardDivider() {
        val text = screen("SettingsScreen.kt")
        assertTrue(text.contains("import com.aurviz.agent.ui.components.CardDivider"))
        assertFalse(text.contains("private fun CardDivider()"))
    }

    @Test
    fun settingsTilesAndPasteFallbackPadded() {
        val text = screen("SettingsScreen.kt")
        assertTrue(text.contains("Modifier.fillMaxWidth().padding(16.dp)"))
        assertTrue(text.contains("contentAlignment = Alignment.Center"))
        assertTrue(text.contains("Finish in the newest browser tab, then paste the full address-bar link here."))
        assertTrue(text.contains("Modifier.weight(1f).height(48.dp)"))
    }

    @Test
    fun settingsTelemetryProportionalAndProvider() {
        val text = screen("SettingsScreen.kt")
        assertTrue(text.contains("providerDisplayName("))
        assertTrue(text.contains("maxScaleMs"))
        assertTrue(text.contains("coerceIn(0.06f, 1f)"))
        assertTrue(text.contains("padding(horizontal = 16.dp, vertical = 12.dp)"))
        assertTrue(text.contains("Modifier.fillMaxWidth().height(48.dp)"))
        assertTrue(text.contains("OEM_AUTOSTART_GUIDANCE"))
        assertTrue(text.contains("Theme.typography.caption"))
    }

    @Test
    fun schedulesSectionsPadded() {
        val text = screen("SchedulesScreen.kt")
        assertTrue(text.contains("import com.aurviz.agent.ui.components.CardDivider"))
        assertTrue(text.contains("Modifier.fillMaxWidth().padding(16.dp)"))
        assertTrue(text.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(text.contains("Modifier.fillMaxWidth().height(48.dp)"))
        assertTrue(text.contains("Modifier.fillMaxWidth().padding(12.dp)"))
        assertTrue(text.contains("heightIn(min = 64.dp)"))
        assertTrue(text.contains("padding(horizontal = 16.dp, vertical = 12.dp)"))
        assertTrue(text.contains("forEachIndexed"))
    }

    @Test
    fun memoryRememberedPadded() {
        val text = screen("MemoryScreen.kt")
        assertTrue(text.contains("Modifier.fillMaxWidth().padding(12.dp)"))
        assertTrue(text.contains("Arrangement.spacedBy(10.dp)"))
    }

    @Test
    fun browserChromeCraft() {
        val text = screen("BrowserScreen.kt")
        assertTrue(text.contains(".height(56.dp)"))
        assertTrue(text.contains("IconButtonSize.Small"))
        assertTrue(text.contains(".height(40.dp)"))
        assertTrue(text.contains("Paste a link, then Go"))
        assertTrue(text.contains("Theme.typography.label"))
        assertTrue(text.contains("maxLines = 1"))
        assertTrue(text.contains("TextOverflow.Ellipsis"))
        assertTrue(text.contains("Modifier.size(56.dp)"))
        assertTrue(text.contains("Modifier.size(26.dp)"))
        assertTrue(text.contains("RoundedCornerShape(14.dp)"))
        assertTrue(text.contains("heightIn(min = 40.dp)"))
        assertFalse(text.contains("ChoiceTile"))
        assertFalse(text.contains("Enter URL"))
    }
}
