package com.minimal.carlauncher

import com.minimal.carlauncher.update.ReleaseInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {

    private fun release(body: String) = ReleaseInfo.from(JSONObject(body))

    @Test
    fun `parses a normal release`() {
        val info = release(
            """
            {
              "tag_name": "v1.2.3",
              "name": "1.2.3",
              "body": "notes",
              "html_url": "https://example.test/r",
              "assets": [
                {"name": "car-launcher-release.apk", "size": 4711234,
                 "browser_download_url": "https://example.test/a.apk"}
              ]
            }
            """.trimIndent()
        )
        assertEquals("v1.2.3", info?.tag)
        assertEquals("https://example.test/a.apk", info?.apkUrl)
        assertEquals(4711234L, info?.apkSize)
        assertTrue(info!!.hasApk)
    }

    @Test
    fun `prefers the release apk over a debug one`() {
        val info = release(
            """
            {
              "tag_name": "1.0.0",
              "assets": [
                {"name": "app-debug.apk", "browser_download_url": "https://example.test/debug.apk"},
                {"name": "app-release.apk", "browser_download_url": "https://example.test/rel.apk"}
              ]
            }
            """.trimIndent()
        )
        assertEquals("https://example.test/rel.apk", info?.apkUrl)
    }

    @Test
    fun `ignores non-apk assets`() {
        val info = release(
            """
            {
              "tag_name": "1.0.0",
              "assets": [
                {"name": "mapping.txt", "browser_download_url": "https://example.test/m.txt"}
              ]
            }
            """.trimIndent()
        )
        assertFalse(info!!.hasApk)
    }

    @Test
    fun `skips drafts and releases with no tag`() {
        assertNull(release("""{"tag_name":"1.0.0","draft":true}"""))
        assertNull(release("""{"name":"no tag here"}"""))
    }

    @Test
    fun `falls back to the tag when the name is missing`() {
        val info = release("""{"tag_name":"v9.9.9"}""")
        assertEquals("v9.9.9", info?.name)
        assertEquals("", info?.body)
    }
}
