package com.aurviz.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T1 (#99): flagship tonal hierarchy, shared primitives & WindowInsets fixes.
// Pure-JVM source guards (Compose is Android-bound); mirrors V2ShellPartATest style.
class FlagshipUiCraftTest {

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }

    private fun mainText(path: String): String =
        File(moduleDir(), "src/main/java/$path").readText()

    @Test
    fun darkThemeUsesObsidianTonalHierarchy() {
        val text = mainText("com/aurviz/agent/ui/theme/Theme.kt")
        assertTrue(text.contains("Color(0xFF0E0F12)"))
        assertTrue(text.contains("Color(0xFF17181C)"))
        assertTrue(text.contains("Color(0xFF22242A)"))
        assertTrue(text.contains("Color(0xFF131418)"))
        assertTrue(text.contains("Color(0xA6F5F5F7)"))
        assertTrue(text.contains("Color(0xFF1084FE)"))
        assertTrue(text.contains("Color(0xFF2E3038)"))
        assertTrue(text.contains("Color(0xFF1B1C22)"))
        assertTrue(text.contains("Color(0xFF2C2E36)"))
        assertTrue(text.contains("Color(0x14F5F5F7)"))
        assertTrue(text.contains("Color(0x7AF5F5F7)"))
        assertTrue(text.contains("Color(0xFF262830)"))
        assertTrue(text.contains("Color(0xFF34D399)"))
        assertTrue(text.contains("Color(0xFFFBBF24)"))
        assertTrue(text.contains("Color(0xFF70B8FF)"))
        assertTrue(text.contains("Color(0x7308090C)"))
    }

    @Test
    fun lightThemeUsesWarmAlabasterCanvas() {
        val text = mainText("com/aurviz/agent/ui/theme/Theme.kt")
        assertTrue(text.contains("Color(0xFFF6F7F9)"))
        assertTrue(text.contains("Color(0xFFEEF0F4)"))
        assertTrue(text.contains("Color(0xA8141518)"))
        assertTrue(text.contains("Color(0xFF0E6CD6)"))
        assertTrue(text.contains("Color(0x26141518)"))
        assertTrue(text.contains("Color(0xFFC92532)"))
        assertTrue(text.contains("Color(0xFFF1F3F6)"))
        assertTrue(text.contains("Color(0x14141518)"))
        assertTrue(text.contains("Color(0x80141518)"))
        assertTrue(text.contains("Color(0xFF057A4B)"))
        assertTrue(text.contains("Color(0xFF9A5B00)"))
        assertTrue(text.contains("Color(0xFF0B63C4)"))
        assertTrue(text.contains("Color(0x4D08090C)"))
    }

    @Test
    fun optionGroupExportsCardDivider() {
        val text = mainText("com/aurviz/agent/ui/components/OptionGroup.kt")
        assertTrue(text.contains("fun CardDivider("))
        assertTrue(text.contains("inset: Dp = 16.dp"))
        assertTrue(text.contains("height(1.dp)"))
        assertTrue(text.contains("Theme.colors.edge"))
        assertTrue(text.contains("RoundedCornerShape(16.dp)"))
        assertTrue(text.contains(".border(0.5.dp, Theme.colors.edge"))
    }

    @Test
    fun feedCardUsesCalmTonalPadding() {
        val text = mainText("com/aurviz/agent/ui/components/FeedCard.kt")
        assertTrue(text.contains(".padding(16.dp)"))
        assertTrue(text.contains("Arrangement.spacedBy(10.dp)"))
        assertTrue(text.contains(".border(0.5.dp, Theme.colors.edge"))
        assertFalse(text.contains(".padding(12.dp)"))
    }

    @Test
    fun choiceTileUsesTonalSelection() {
        val text = mainText("com/aurviz/agent/ui/components/ChoiceTile.kt")
        assertTrue(text.contains("Theme.colors.edge"))
        assertTrue(text.contains("Theme.colors.inset"))
        assertTrue(text.contains("if (selected) 1.dp else 0.5.dp"))
        assertTrue(text.contains("heightIn(min = 44.dp)"))
        assertFalse(text.contains("else 1.dp,"))
    }

    @Test
    fun textFieldUsesInsetWell() {
        val text = mainText("com/aurviz/agent/ui/components/TextField.kt")
        assertTrue(text.contains(".background(Theme.colors.inset)"))
        assertTrue(text.contains("if (focused) 1.dp else 0.5.dp"))
        assertTrue(text.contains("Theme.colors.edge"))
        assertTrue(text.contains("Theme.typography.body.copy(color = Theme.colors.onSurface)"))
    }

    @Test
    fun screenScaffoldConsumesStatusBars() {
        val text = mainText("com/aurviz/agent/ui/components/ScreenScaffold.kt")
        assertTrue(text.contains(".statusBarsPadding()"))
        assertTrue(text.contains("consumeWindowInsets(WindowInsets.statusBars)"))
        assertTrue(text.contains("import androidx.compose.foundation.layout.consumeWindowInsets"))
    }

    @Test
    fun sideDrawerUnclipsFooterWithBottomInsets() {
        val text = mainText("com/aurviz/agent/ui/components/SideDrawer.kt")
        assertTrue(text.contains("WindowInsets.safeDrawing"))
        assertTrue(text.contains("WindowInsets.navigationBars"))
        assertTrue(text.contains("WindowInsets.systemGestures"))
        assertTrue(text.contains("only(WindowInsetsSides.Bottom)"))
        assertTrue(text.contains(".padding(bottom = 16.dp)"))
        assertFalse(text.contains(".padding(bottom = 8.dp)"))
    }

    @Test
    fun primitivesStayOnStockFoundation() {
        val paths =
            listOf(
                "com/aurviz/agent/ui/components/OptionGroup.kt",
                "com/aurviz/agent/ui/components/FeedCard.kt",
                "com/aurviz/agent/ui/components/ChoiceTile.kt",
                "com/aurviz/agent/ui/components/TextField.kt",
                "com/aurviz/agent/ui/components/ScreenScaffold.kt",
                "com/aurviz/agent/ui/components/SideDrawer.kt",
            )
        for (path in paths) {
            val text = mainText(path)
            assertFalse("$path must not use Material", text.contains("material"))
        }
    }
}
