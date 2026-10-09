package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfWriterTest {
    private fun make(n: Int, jpegSize: (Int) -> Int = { 100 + it }): Pair<ByteArray, List<ByteArray>> {
        val out = ByteArrayOutputStream()
        val w = PdfWriter(out)
        val jpegs = (0 until n).map { i -> ByteArray(jpegSize(i)) { (it % 251).toByte() } }
        jpegs.forEachIndexed { i, j -> w.addJpegPage(j, 800 + i, 1200, 400 + i, 600) }
        w.finish()
        return out.toByteArray() to jpegs
    }

    private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    @Test
    fun manyPagesBecomeOnePdfWithTheRightCount() {
        for (n in listOf(1, 2, 30, 50)) {
            val (bytes, _) = make(n)
            val t = text(bytes)
            assertTrue(t.startsWith("%PDF-1.4"))
            assertTrue(t.endsWith("%%EOF\n"))
            assertTrue("/Count $n >>" in t)
            assertEquals(n, Regex("/Type /Page /Parent").findAll(t).count())
            assertEquals(n, Regex("/Subtype /Image").findAll(t).count())
        }
    }

    @Test
    fun everyCrossReferenceEntryPointsAtItsObject() {
        val (bytes, _) = make(7)
        val t = text(bytes)
        val xrefAt = Regex("startxref\n(\\d+)\n").find(t)!!.groupValues[1].toInt()
        assertTrue(t.substring(xrefAt).startsWith("xref\n0 ${3 + 7 * 3}\n"))
        val entries = Regex("(\\d{10}) 00000 n ").findAll(t.substring(xrefAt)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2 + 7 * 3, entries.size) // objects 1..(2+3n)
        entries.forEachIndexed { i, off -> assertTrue("object ${i + 1} at $off", t.substring(off).startsWith("${i + 1} 0 obj\n")) }
    }

    @Test
    fun theJpegBytesAreEmbeddedUntouchedAndInOrder() {
        val (bytes, jpegs) = make(4) { 1000 + it * 17 }
        val t = text(bytes)
        var from = 0
        for (j in jpegs) {
            val at = indexOf(bytes, j, from)
            assertTrue("a page's JPEG is in the file, after the previous one", at >= from)
            val before = t.substring(maxOf(0, at - 200), at)
            val len = Regex("/Length (\\d+) >>\nstream\n$").find(before)!!.groupValues[1].toInt()
            assertEquals(j.size, len) // its declared length is exact
            from = at + j.size
        }
    }

    @Test
    fun pageOrderIsTheOrderAdded() {
        val (bytes, _) = make(3)
        assertTrue(text(bytes).contains("/Kids [3 0 R 6 0 R 9 0 R]"))
    }

    @Test
    fun aPdfWithoutPagesIsRefused() {
        assertThrows(IllegalStateException::class.java) { PdfWriter(ByteArrayOutputStream()).finish() }
    }

    @Test
    fun pageSizeKeepsTheShapeAndCapsTheLongSideAtA4() {
        assertEquals(595 to 842, ScanActivity.pageSize(1190, 1684))
        assertEquals(300 to 400, ScanActivity.pageSize(300, 400)) // small photos are not enlarged
        assertEquals(842 to 421, ScanActivity.pageSize(2000, 1000))
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) { for (k in n.indices) if (h[i + k] != n[k]) continue@outer; return i }
        return -1
    }
}
