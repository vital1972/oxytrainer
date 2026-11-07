package com.example.oxycaretrainer

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun versionComparison() {
        assertTrue(Versions.isNewer("v1.3.0", "1.2"))
        assertTrue(Versions.isNewer("1.10", "1.9"))
        assertFalse(Versions.isNewer("1.2.0", "1.2"))
        assertFalse(Versions.isNewer("1.1.9", "1.2"))
        assertTrue(Versions.isNewer("2", "1.99.99"))
        assertEquals(listOf(1, 3, 0), Versions.parse("v1.3.0-beta"))
    }

    private val base = "https://github.com/vital1972/oxytrainer/releases/download/v1.3.0/"

    @Test fun parsesRelease() {
        val json = """{"tag_name":"v1.3.0","body":"Новое","draft":false,"prerelease":false,"assets":[
            {"name":"oxycare-1.3.0.apk","browser_download_url":"${base}oxycare-1.3.0.apk","size":1234},
            {"name":"oxycare-1.3.0.apk.sha256","browser_download_url":"${base}oxycare-1.3.0.apk.sha256","size":90}]}"""
        val r = UpdateChecker.parseLatest(json)!!
        assertEquals("1.3.0", r.version); assertEquals(1234L, r.apkSize)
        assertEquals("${base}oxycare-1.3.0.apk", r.apkUrl); assertEquals("${base}oxycare-1.3.0.apk.sha256", r.sha256Url)
    }

    @Test fun ignoresForeignDownloadUrls() {
        val json = """{"tag_name":"v9","assets":[{"name":"evil.apk","browser_download_url":"https://evil.example/evil.apk","size":1}]}"""
        assertNull(UpdateChecker.parseLatest(json))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://github.com/someone/else/releases/download/v1/a.apk"))
    }

    @Test fun rejectsDraftsAndGarbage() {
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v2","draft":true,"assets":[]}"""))
        assertNull(UpdateChecker.parseLatest("nope"))
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"v2","assets":[]}"""))
    }
}
