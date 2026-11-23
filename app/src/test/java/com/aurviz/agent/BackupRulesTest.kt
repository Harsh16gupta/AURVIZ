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
}
