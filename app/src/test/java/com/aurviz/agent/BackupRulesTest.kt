package com.aurviz.agent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

// Ticket #33: tokens (TokenVault) and mail-derived Memory must never land in
// cloud backups or device-to-device transfers. Seam: the manifest + rules XML
// source files, parsed as XML. Catches missing/false backup attributes;
// misses merged-manifest interactions with library manifests.
class BackupRulesTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: break
        }
        throw AssertionError("Could not locate app module dir from user.dir")
    }

    private fun parse(file: File): Element {
        assertTrue("expected file missing: $file", file.exists())
        val db = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return db.newDocumentBuilder().parse(file).documentElement
    }

    @Test
    fun manifestDisablesBackupAndPointsAtExtractionRules() {
        val app = parse(File(moduleDir(), "src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals(
            "@xml/data_extraction_rules",
            app.getAttributeNS(androidNs, "dataExtractionRules")
        )
    }

    @Test
    fun extractionRulesDenyCloudAndDeviceTransfer() {

        val root = parse(File(moduleDir(), "src/main/res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", root.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = root.getElementsByTagName(section).item(0) as Element
            val excludes = node.getElementsByTagName("exclude")
            assertTrue("$section must exclude app data", excludes.length > 0)
            assertEquals(
                "$section must not include any data",
                0,
                node.getElementsByTagName("include").length
            )
            val domains = (0 until excludes.length)
                .map { (excludes.item(it) as Element).getAttribute("domain") }
                .toSet()
            assertTrue(
                "$section must cover file, database and sharedpref domains",
                domains.containsAll(setOf("file", "database", "sharedpref"))
            )
        }
    }

    // Ticket #108 (T3): MainActivity is a Share Sheet target (ACTION_SEND
    // text/plain), runs singleTop so shares route to onNewIntent, and
    // declares the launcher shortcuts meta-data.
    @Test
    fun manifestDeclaresShareTargetAndShortcuts() {
        val manifest = parse(File(moduleDir(), "src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        var mainActivity: Element? = null
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            if (el.getAttributeNS(androidNs, "name") == ".MainActivity") mainActivity = el
        }
        val activity = requireNotNull(mainActivity) { "MainActivity missing from manifest" }
        assertEquals("singleTop", activity.getAttributeNS(androidNs, "launchMode"))
        var hasSendFilter = false
        var hasShortcutsMeta = false
        val filters = activity.getElementsByTagName("intent-filter")
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val actions = filter.getElementsByTagName("action")
            var hasSend = false
            var hasDefault = false
            for (j in 0 until actions.length) {
                val name = (actions.item(j) as Element).getAttributeNS(androidNs, "name")
                if (name == "android.intent.action.SEND") hasSend = true
            }
            val categories = filter.getElementsByTagName("category")
            for (j in 0 until categories.length) {
                val name = (categories.item(j) as Element).getAttributeNS(androidNs, "name")
                if (name == "android.intent.category.DEFAULT") hasDefault = true
            }
            val datas = filter.getElementsByTagName("data")
            var hasPlainText = false
            for (j in 0 until datas.length) {
                val mime = (datas.item(j) as Element).getAttributeNS(androidNs, "mimeType")
                if (mime == "text/plain") hasPlainText = true
            }
            if (hasSend && hasDefault && hasPlainText) hasSendFilter = true
        }
        val metas = activity.getElementsByTagName("meta-data")
        for (i in 0 until metas.length) {
            val meta = metas.item(i) as Element
            if (meta.getAttributeNS(androidNs, "name") == "android.app.shortcuts" &&
                meta.getAttributeNS(androidNs, "resource") == "@xml/shortcuts"
            ) {
                hasShortcutsMeta = true
            }
        }
        assertTrue("MainActivity must declare an ACTION_SEND text/plain intent-filter", hasSendFilter)
        assertTrue("MainActivity must declare android.app.shortcuts meta-data", hasShortcutsMeta)
    }

    // Ticket #108 (T3): the 3 launcher shortcuts target MainActivity with
    // the actions AgentViewModel.handleIncomingIntent routes.
    @Test
    fun shortcutsDeclareVoiceNewSessionAndSchedules() {
        val root = parse(File(moduleDir(), "src/main/res/xml/shortcuts.xml"))
        assertEquals("shortcuts", root.tagName)
        val shortcuts = root.getElementsByTagName("shortcut")
        assertEquals(3, shortcuts.length)
        val actions = mutableSetOf<String>()
        for (i in 0 until shortcuts.length) {
            val shortcut = shortcuts.item(i) as Element
            val intents = shortcut.getElementsByTagName("intent")
            assertTrue("shortcut ${shortcut.getAttributeNS(androidNs, "shortcutId")} must declare an intent", intents.length > 0)
            for (j in 0 until intents.length) {
                val el = intents.item(j) as Element
                actions += el.getAttributeNS(androidNs, "action")
                assertEquals("com.aurviz.agent", el.getAttributeNS(androidNs, "targetPackage"))
                assertEquals("com.aurviz.agent.MainActivity", el.getAttributeNS(androidNs, "targetClass"))
            }
        }
        assertTrue(actions.contains("com.aurviz.agent.action.VOICE_COMMAND"))
        assertTrue(actions.contains("com.aurviz.agent.action.NEW_SESSION"))
        assertTrue(actions.contains("com.aurviz.agent.action.SCHEDULES"))
    }

    // Ticket #108 (T3): the shortcut labels resolve to shipped strings.
    @Test
    fun shortcutLabelsResolveToStrings() {
        val strings = parse(File(moduleDir(), "src/main/res/values/strings.xml"))
        val names = mutableSetOf<String>()
        val items = strings.getElementsByTagName("string")
        for (i in 0 until items.length) {
            names += (items.item(i) as Element).getAttribute("name")
        }
        assertTrue(names.contains("shortcut_voice_short"))
        assertTrue(names.contains("shortcut_new_session_short"))
        assertTrue(names.contains("shortcut_schedules_short"))
    }

    // Ticket #110 (T5): release hardening ships R8 rules for Tink/Room/
    // WorkManager/OkHttp and wires them into the release build type.
    @Test
    fun proguardRulesExistAndAreWiredIntoRelease() {
        val module = moduleDir()
        val rules = File(module, "proguard-rules.pro")
        assertTrue("app/proguard-rules.pro must exist", rules.exists())
        val text = rules.readText()
        assertTrue("rules must keep Tink", text.contains("-keep class com.google.crypto.tink.**"))
        assertTrue("rules must keep RoomDatabase", text.contains("extends androidx.room.RoomDatabase"))
        assertTrue("rules must keep Room entities", text.contains("@androidx.room.Entity"))
        assertTrue("rules must keep Room DAOs", text.contains("@androidx.room.Dao"))
        assertTrue("rules must keep WorkManager workers", text.contains("extends androidx.work.Worker"))
        assertTrue("rules must silence OkHttp", text.contains("okhttp3.**"))
        val gradle = File(module, "build.gradle.kts").readText()
        assertTrue(
            "release build type must reference proguard-rules.pro",
            gradle.contains("proguard-rules.pro")
        )
    }
}
