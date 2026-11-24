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

    // T5 (#103): final flagship contrast, glossary, tap-target & card QA sweep.
    // Each test below pins one acceptance bullet; all are pure-JVM source
    // guards (Compose is Android-bound).

    @Test
    fun commandScreenKeepsSingleImeInset() {
        val text = mainText("com/aurviz/agent/ui/screens/CommandScreen.kt")
        val imeInsets = text.split(".imePadding()").size - 1
        assertTrue("expected exactly one .imePadding(), found $imeInsets", imeInsets == 1)
        assertFalse(
            "duplicate IME inset via navigation-bars union",
            text.contains("WindowInsets.navigationBars.union(WindowInsets.ime)"),
        )
    }

    @Test
    fun statusBarInsetConsumedOnceInScaffold() {
        val text = mainText("com/aurviz/agent/ui/components/ScreenScaffold.kt")
        assertTrue(text.contains("consumeWindowInsets(WindowInsets.statusBars)"))
    }

    @Test
    fun drawerFooterKeepsGestureAwareBottomInset() {
        val drawer = mainText("com/aurviz/agent/ui/components/SideDrawer.kt")
        assertTrue(drawer.contains("safeDrawing"))
        assertTrue(drawer.contains("systemGestures"))
        assertTrue(drawer.contains("only(WindowInsetsSides.Bottom)"))
        val nav = mainText("com/aurviz/agent/ui/AgentNav.kt")
        assertTrue(nav.contains("heightIn(min = 64.dp)"))
    }

    @Test
    fun noDimmingAlphaAndTonalTokensStayDistinct() {
        val uiDir = File(moduleDir(), "src/main/java/com/aurviz/agent/ui")
        val uiFiles = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected UI sources under ui/", uiFiles.isNotEmpty())
        for (file in uiFiles) {
            assertFalse(
                "${file.name} must not dim with .alpha(0.72f)",
                file.readText().contains(".alpha(0.72f)"),
            )
        }
        val theme = mainText("com/aurviz/agent/ui/theme/Theme.kt")
        val dark = theme.substringAfter("val DarkThemeColors").substringBefore("isDark = true")
        val light = theme.substringAfter("val LightThemeColors").substringBefore("isDark = false")
        val keys = listOf("background", "surface", "surfaceVariant", "inset")
        fun token(block: String, key: String): String {
            val match = Regex("(?m)^\\s*$key = Color\\((0x[0-9A-Fa-f]+)\\)").find(block)
            return requireNotNull(match) { "missing $key token" }.groupValues[1]
        }
        for (key in keys) {
            assertTrue("DarkThemeColors missing $key", dark.contains(key))
            assertTrue("LightThemeColors missing $key", light.contains(key))
        }
        val darkTokens = keys.map { token(dark, it) }
        val lightTokens = keys.map { token(light, it) }
        assertTrue("Midnight tonal tokens must be distinct", darkTokens.toSet().size == 4)
        assertTrue("Daylight tonal tokens must be distinct", lightTokens.toSet().size == 4)
        for (i in keys.indices) {
            assertFalse(
                "Midnight/Daylight parity: ${keys[i]} must differ per theme",
                darkTokens[i] == lightTokens[i],
            )
        }
    }

    @Test
    fun glossaryStaysCleanAcrossScreensSheetsAndComponents() {
        val bases =
            listOf(
                "com/aurviz/agent/ui/screens",
                "com/aurviz/agent/ui/sheets",
                "com/aurviz/agent/ui/components",
            )
        // Banned GLOSSARY.md display words. Matching is case-insensitive
        // because user-visible copy must avoid the term in any casing.
        val banned = listOf("more options", "command options", "history", "chatbot", "assistant", "alarm", "timer", "cron", "send_email")
        for (base in bases) {
            val dir = File(moduleDir(), "src/main/java/$base")
            for (file in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                val lines = file.readLines()
                for ((index, line) in lines.withIndex()) {
                    // Exemptions (not user-visible): tool-registry keys passed
                    // through toolLabel() (ToolLabels.kt maps them to display
                    // names so no raw snake_case ever reaches the screen),
                    // tool-name comparisons, and the private prefs key.
                    if (line.contains("toolLabel(\"") || line.contains(".name == \"") || line.contains("const val KEY_")) continue
                    // Mandated verbatim brand copy (CommandScreen hero):
                    // "Your personal assistant that knows you." — spec
                    // requires this exact string, so exempt it from the
                    // "assistant" ban.
                    if (line.contains("Your personal assistant that knows you.")) continue
                    val literals = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }
                    for (literal in literals) {
                        val lowered = literal.lowercase()
                        for (word in banned) {
                            assertFalse(
                                "${file.name}:${index + 1} shows banned copy \"$word\" in \"$literal\"",
                                lowered.contains(word),
                            )
                        }
                    }
                }
            }
        }
        val settings = mainText("com/aurviz/agent/ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("Page(title = \"Settings\","))
    }

    @Test
    fun zeroPaddingCardBugsStayFixed() {

        val email = mainText("com/aurviz/agent/ui/sheets/EmailConfirmationSheet.kt")
        assertFalse("field stack must not re-wrap in a Section card", email.contains("Section("))
        val forget = mainText("com/aurviz/agent/ui/sheets/ForgetMemorySheet.kt")
        assertTrue(forget.contains(".padding(16.dp)"))
        val schedules = mainText("com/aurviz/agent/ui/screens/SchedulesScreen.kt")
        assertTrue(schedules.contains("Modifier.fillMaxWidth().height(48.dp)"))
        assertTrue(schedules.contains("\"Preview Schedule\""))
        val browser = mainText("com/aurviz/agent/ui/screens/BrowserScreen.kt")
        assertTrue(browser.contains("\"Paste a link, then Go\""))
        assertTrue(browser.contains("Theme.typography.label"))
        assertTrue(browser.contains("singleLine = true"))
        assertTrue(browser.contains("maxLines = 1"))
        assertTrue(browser.contains("TextOverflow.Ellipsis"))
    }

    // Ticket #108 (T3): 1-tap Agent diagnostics card in Settings — stock
    // foundation, VelaTheme, no new icons; re-run label shows ready count.
    @Test
    fun settingsHasSystemDiagnosticsCard() {
        val text = mainText("com/aurviz/agent/ui/screens/SettingsScreen.kt")
        assertTrue(text.contains("Section(\"System Diagnostics\")"))
        assertTrue(text.contains("state.diagnostics.items"))
        assertTrue(text.contains("\"Run Diagnostics\""))
        assertTrue(text.contains("Re-run Diagnostics ("))
        assertTrue(text.contains("onRunDiagnostics"))
        assertTrue(text.contains("ButtonVariant.Secondary"))
        assertTrue(text.contains("Modifier.fillMaxWidth().height(48.dp)"))
        assertTrue(text.contains("CardDivider()"))
        assertFalse(text.contains("material"))
    }
}
