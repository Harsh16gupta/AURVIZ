package com.aira.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T3 (#101): VoiceCommandSheet open-canvas Ramble, EmailConfirmationSheet
// Proton field stack without double-nested Section, ForgetMemorySheet
// Signal destructive with padded preview rows. Pure-JVM source guards
// (Compose is Android-bound); mirrors FlagshipT2CraftTest style.
class FlagshipT3SheetsTest {

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
    fun voiceSheetHasSingleCloseAndOpenCanvasTranscript() {
        val text = mainText("com/aira/agent/ui/sheets/VoiceCommandSheet.kt")
        // The single top-left X lives in ModalBottomSheet; the body must
        // not render a second X or cancel button.
        assertFalse("second close X in VoiceCommandSheet", text.contains("Icons.X"))
        assertFalse("cancel button in VoiceCommandSheet", text.contains("cancel", ignoreCase = true))
        // Open canvas: zero enclosing border box around the transcript.
        assertFalse("border box around transcript", text.contains(".border("))
        assertTrue(text.contains("heightIn(min = 88.dp)"))
        assertTrue(text.contains("padding(horizontal = 8.dp)"))
        assertTrue(text.contains("Tap the mic and speak — typing always works too."))
        assertTrue(text.contains("fontSize = 20.sp"))
        assertTrue(text.contains("lineHeight = 28.sp"))
        assertTrue(text.contains("maxLines = 4"))
        assertTrue(text.contains("\"Listening...\""))
        assertTrue(text.contains("\"Paused\""))
        assertTrue(text.contains("\"Ready to listen\""))
        assertTrue(text.contains("if (state.listening) Theme.colors.primary"))
    }

    @Test
    fun voiceSheetControlsAreBorderlessTonalCircles() {
        val text = mainText("com/aira/agent/ui/sheets/VoiceCommandSheet.kt")
        assertTrue(text.contains("Modifier.fillMaxWidth().height(48.dp)"))
        assertTrue(text.contains("4.dp.toPx()"))
        assertTrue(text.contains(".size(64.dp)"))
        assertTrue(text.contains(".size(52.dp)"))
        assertTrue(text.contains("0.38f"))
        assertTrue(text.contains("Icons.Stop"))
        assertTrue(text.contains("Icons.Microphone"))
        assertTrue(text.contains("Icons.Check"))
        assertTrue(text.contains("if (canConfirm) Theme.colors.primary"))
        assertTrue(text.contains("Theme.colors.surfaceVariant"))
    }

    @Test
    fun emailSheetDropsSectionForOpenProtonStack() {
        val text = mainText("com/aira/agent/ui/sheets/EmailConfirmationSheet.kt")
        assertFalse("double-nested Section card", text.contains("Section("))
        assertTrue(text.contains("\"Send this email?\""))
        assertTrue(
            text.contains(
                "Review and edit before confirming — nothing sends until you tap Confirm send.",
            ),
        )
        assertTrue(text.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(text.contains("Arrangement.spacedBy(2.dp)"))
        assertTrue(text.contains("heightIn(min = 36.dp)"))
        assertTrue(text.contains("Icons.PencilSimple"))
        assertTrue(text.contains("Modifier.size(14.dp)"))
        assertTrue(text.contains("FontWeight.SemiBold"))
        assertTrue(text.contains("New recipient — never emailed before."))
        assertTrue(text.contains("RoundedCornerShape(12.dp)"))
        assertTrue(text.contains("warning.copy(alpha = 0.14f)"))
        assertTrue(text.contains("Theme.colors.inset"))
        assertTrue(text.contains("RoundedCornerShape(16.dp)"))
        assertTrue(text.contains("0.5.dp, Theme.colors.edge"))
        assertTrue(text.contains("CardDivider(inset = 16.dp)"))
        assertTrue(text.contains("label = \"Message\""))
        assertTrue(text.contains("minLines = 4"))
        assertTrue(text.contains("Arrangement.spacedBy(10.dp)"))
        assertTrue(text.contains("Modifier.weight(1f).height(48.dp)"))
        assertTrue(text.contains("\"Confirm send\""))
        assertTrue(text.contains("\"Reject\""))
    }

    @Test
    fun forgetSheetPadsPreviewRowsWithHairlineDividers() {
        val text = mainText("com/aira/agent/ui/sheets/ForgetMemorySheet.kt")
        assertTrue(text.contains("Section(\"Delete these?\")"))
        assertTrue(text.contains(".padding(16.dp)"))
        assertTrue(text.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(text.contains("height(1.dp)"))
        assertTrue(text.contains("Theme.colors.edge"))
        assertTrue(text.contains("maxLines = 3"))
        assertTrue(text.contains("TextOverflow.Ellipsis"))
        assertTrue(text.contains("\"+\$overflow more\""))
        assertTrue(text.contains("This only deletes the"))
        assertTrue(text.contains("Memories"))
        assertTrue(text.contains("ButtonVariant.Destructive"))
        assertTrue(text.contains("Icons.Trash"))
        assertTrue(text.contains("\"Keep\""))
    }

    @Test
    fun t3SheetsStayOnStockFoundation() {
        val paths =
            listOf(
                "com/aira/agent/ui/sheets/VoiceCommandSheet.kt",
                "com/aira/agent/ui/sheets/EmailConfirmationSheet.kt",
                "com/aira/agent/ui/sheets/ForgetMemorySheet.kt",
            )
        for (path in paths) {
            val text = mainText(path)
            assertFalse("$path must not use Material", text.contains("material"))
        }
    }
}
