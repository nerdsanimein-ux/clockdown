package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateTest {
    private fun release(tag: String, assets: String = """[{"name":"clockdown-1.1.apk","size":10248563,"browser_download_url":"https://github.com/x/clockdown/releases/download/2/clockdown-1.1.apk","digest":"sha256:${"ab".repeat(32)}"}]""") =
        """{"tag_name":"$tag","name":"Clockdown 1.1","body":"## What's new\r\n- Faster **sync**\r\n- New `widgets`\r\n\r\n\r\n\r\nThanks!","assets":$assets}"""

    @Test
    fun parsesTagAsVersionCode() {
        val u = parseRelease(release("2"))!!
        assertEquals(2, u.versionCode)
        assertEquals("1.1", u.versionName) // "Clockdown 1.1" -> "1.1"
        assertEquals(10248563L, u.apkSize)
        assertEquals("ab".repeat(32), u.sha256)
        assertEquals(12, parseRelease(release("v12"))!!.versionCode) // a leading v is fine
    }

    @Test
    fun rejectsReleasesItCannotUse() {
        assertNull(parseRelease(release("beta-2")))          // tag isn't a whole number
        assertNull(parseRelease(release("2", assets = "[]"))) // no APK attached
        assertNull(parseRelease("<html>rate limited</html>"))  // not JSON at all
    }

    @Test
    fun ignoresAMissingOrShortDigest() {
        val short = release("3", assets = """[{"name":"a.apk","size":1,"browser_download_url":"https://x/a.apk","digest":"sha256:abc"}]""")
        assertNull(parseRelease(short)!!.sha256)
    }

    @Test
    fun turnsMarkdownNotesIntoPlainText() {
        assertEquals("• Faster sync\n• New widgets\n\nThanks!", cleanNotes(parseRelease(release("2"))!!.notes))
        assertEquals("Fixes\n• One bug", cleanNotes("## Fixes\n- One bug")) // other headings are kept
    }

    @Test
    fun summaryKeepsFirstLinesAndSaysThereIsMore() {
        assertEquals("• a\n• b", notesSummary("## What's new\n- a\n- b"))
        assertEquals("1\n2\n…and more", notesSummary("1\n2\n3", maxLines = 2))
        assertEquals("x".repeat(9) + "…", notesSummary("x".repeat(30), maxChars = 10))
    }

    @Test
    fun keepAliveRunsOnlyBetweenSevenAndEleven() {
        fun at(h: Int, m: Int = 0) = keepAliveWindow(java.time.LocalTime.of(h, m))
        assertEquals(false, at(6, 59)); assertEquals(true, at(7)); assertEquals(true, at(22, 59)); assertEquals(false, at(23)); assertEquals(false, at(3))
    }
}
