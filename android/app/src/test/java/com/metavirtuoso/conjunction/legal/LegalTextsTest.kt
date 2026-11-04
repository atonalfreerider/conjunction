package com.metavirtuoso.conjunction.legal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextsTest {
    private val legalDir = File(System.getProperty("conjunction.legalDir") ?: "../../legal")

    @Test
    fun parsesVersionFromHeaderTable() {
        assertEquals("1.0", LegalTexts.parseVersion("| Document | `WAIVER` |\n| Version | 1.0 (draft of 2026-11-03) |\n"))
        assertEquals("2.1", LegalTexts.parseVersion("| Version | 2.1 |\n"))
        assertEquals("unversioned", LegalTexts.parseVersion("no table"))
    }

    @Test
    fun everyBundledDocumentExistsAndHasAVersion() {
        LegalDoc.entries.forEach { doc ->
            val file = File(legalDir, doc.asset.removePrefix("legal/"))
            assertTrue("missing ${file.absolutePath}", file.exists())
            assertTrue("${doc.key} has no version", LegalTexts.parseVersion(file.readText()) != "unversioned")
        }
    }

    @Test
    fun sha256IsHexOf64Chars() {
        val hash = LegalTexts.sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun consentRecordJsonRoundTrip() {
        val r = ConsentRecord("WAIVER", "1.0", "2026-11-03T10:00:00Z", "1.0.0", "android", "ab".repeat(32), withdrawn = true)
        assertEquals(r, ConsentRecord.fromJson(r.toJson()))
    }
}
