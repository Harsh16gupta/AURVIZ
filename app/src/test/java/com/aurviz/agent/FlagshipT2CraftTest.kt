package com.aurviz.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T2 (#100): CommandScreen IME fix, centered ModelPill, connected Plan
// timeline, SideDrawer Session pill & Onboarding polish. Pure-JVM source
// guards (Compose is Android-bound); mirrors FlagshipUiCraftTest style.
class FlagshipT2CraftTest {

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
    fun modelPillIsCenteredTonalWithoutBadge() {
        val text = mainText("com/aurviz/agent/ui/components/ModelPill.kt")
        assertTrue(text.contains("heightIn(min = 36.dp)"))
        assertTrue(text.contains("Theme.colors.surfaceVariant"))
        assertTrue(text.contains("badge: String? = null"))
        assertFalse("header pill must not render badge text", text.contains("Text(badge"))
        assertFalse("header pill must not render badge text", text.contains("if (badge"))
    }

    @Test
    fun commandScreenHasSingleImeInsetAndWorkspaceCopy() {
        val text = mainText("com/aurviz/agent/ui/screens/CommandScreen.kt")
        assertTrue(text.contains(".navigationBarsPadding()"))
        assertTrue(text.contains(".imePadding()"))
        assertFalse(
            "duplicate IME inset on Composer wrapper",
            text.contains("windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))"),
        )
        assertTrue(text.contains("\"Workspace\""))
        assertFalse("banned copy", text.contains("More options"))
    }

    @Test
    fun commandTopBarActionsMeetTouchTarget() {
        val text = mainText("com/aurviz/agent/ui/screens/CommandScreen.kt")
        assertFalse("44.dp header actions are below target", text.contains("Modifier.size(44.dp)"))
        assertTrue(text.contains("Modifier.size(48.dp)"))
    }

    @Test
    fun planStepsGroupInConnectedTonalCard() {
        val text = mainText("com/aurviz/agent/ui/screens/CommandScreen.kt")
        assertTrue(text.contains("visiblePlanSteps(record.steps)"))
        assertTrue(text.contains("visiblePlanSteps(state.timeline)"))
        assertTrue(text.contains("RoundedCornerShape(12.dp)"))
        assertTrue(text.contains(".background(Theme.colors.surface)"))
        assertTrue(text.contains(".border(1.dp, Theme.colors.edge"))
        assertTrue(text.contains("Modifier.size(18.dp)"))
        assertTrue(text.contains("Modifier.size(12.dp)"))
        assertTrue(text.contains("Modifier.size(6.dp)"))
        assertTrue(text.contains("Theme.colors.success.copy(alpha = 0.14f)"))
        assertTrue(text.contains("Theme.colors.destructive.copy(alpha = 0.14f)"))
        assertTrue(text.contains("Theme.colors.primary.copy(alpha = 0.14f)"))
        assertTrue(text.contains("Theme.colors.inset"))
        assertTrue(text.contains("Theme.typography.code"))
        assertTrue(text.contains("heightIn(min = 36.dp)"))
    }

    @Test
    fun composerPillExpandsWithoutClipping() {
        val text = mainText("com/aurviz/agent/ui/components/Composer.kt")
        assertTrue(text.contains("heightIn(min = 52.dp)"))
        assertTrue(text.contains("RoundedCornerShape(26.dp)"))
        assertFalse(text.contains("max = 56.dp"))
    }

    @Test
    fun drawerHasMemoryRowAndBorderlessSessionPill() {
        val text = mainText("com/aurviz/agent/ui/AgentNav.kt")
        assertTrue(text.contains("\"Memories\""))
        assertTrue(text.contains("Dests.MEMORY"))
        assertTrue(text.contains("Modifier.size(48.dp)"))
        assertTrue(text.contains("heightIn(min = 64.dp)"))
        assertFalse("SessionRow must stay borderless", text.contains(".border("))
        assertTrue(text.contains("Theme.typography.label"))
        assertTrue(text.contains("Theme.colors.onBackgroundMuted"))
        assertTrue(text.contains("Modifier.size(44.dp)"))
        assertTrue(text.contains("\"Delete Session\""))
    }

    @Test
    fun onboardingUsesLargeTouchTargetsAndHairlineCards() {
        val text = mainText("com/aurviz/agent/ui/screens/OnboardingScreen.kt")
        assertTrue(text.contains("height(56.dp)"))
        assertTrue(text.contains("Modifier.size(48.dp)"))
        assertTrue(text.contains("heightIn(min = 48.dp)"))
        assertTrue(text.contains(".border(0.5.dp, Theme.colors.edge"))
        assertFalse("1.dp card borders are too heavy", text.contains(".border(1.dp, Theme.colors.edge, Theme.shapes.medium)"))
        assertTrue(text.contains("Modifier.fillMaxWidth().height(48.dp)"))
    }

    @Test
    fun t2SurfacesStayOnStockFoundation() {
        val paths =
            listOf(
                "com/aurviz/agent/ui/components/ModelPill.kt",
                "com/aurviz/agent/ui/components/Composer.kt",
                "com/aurviz/agent/ui/screens/CommandScreen.kt",
                "com/aurviz/agent/ui/screens/OnboardingScreen.kt",
                "com/aurviz/agent/ui/AgentNav.kt",
            )
        for (path in paths) {
            val text = mainText(path)
            assertFalse("$path must not use Material", text.contains("material"))
        }
    }
}
