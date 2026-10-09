package com.rishabh.clockdown

import java.io.OutputStream

/**
 * Writes a PDF with one JPEG per page, straight from the JPEG's own bytes (no re-compression, so a 30-page scan stays a few
 * megabytes; Android's PdfDocument stores bitmaps uncompressed-ish and would be many times bigger). Pages are added one at a time and
 * written out at once, so a long scan never has to sit in memory together. Pure, so it is unit-tested.
 *
 * Object numbers: 1 = catalog, 2 = the page list (written last), then three per page: the page, its drawing commands, its image.
 */
class PdfWriter(private val out: OutputStream) {
    private var written = 0L
    private val offsets = HashMap<Int, Long>()
    private val pageObjects = mutableListOf<Int>()

    init {
        raw("%PDF-1.4\n%âãÏÓ\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
    }

    private fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
    private fun raw(b: ByteArray) { out.write(b); written += b.size }
    private fun obj(n: Int, body: String) { offsets[n] = written; raw("$n 0 obj\n$body\nendobj\n") }

    /** Adds a page showing [jpeg] (a baseline colour JPEG of [width] x [height] pixels) at [widthPt] x [heightPt] points. */
    fun addJpegPage(jpeg: ByteArray, width: Int, height: Int, widthPt: Int, heightPt: Int) {
        require(width > 0 && height > 0 && widthPt > 0 && heightPt > 0)
        val page = 3 + pageObjects.size * 3
        val content = "q $widthPt 0 0 $heightPt 0 0 cm /Im0 Do Q"
        obj(page, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $widthPt $heightPt] /Resources << /XObject << /Im0 ${page + 2} 0 R >> >> /Contents ${page + 1} 0 R >>")
        obj(page + 1, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        offsets[page + 2] = written
        raw("${page + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        raw(jpeg)
        raw("\nendstream\nendobj\n")
        pageObjects += page
    }

    val pageCount get() = pageObjects.size

    /** Writes the page list, the cross-reference table and the trailer. Does not close the stream. */
    fun finish() {
        check(pageObjects.isNotEmpty()) { "a PDF needs at least one page" }
        obj(2, "<< /Type /Pages /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjects.size} >>")
        val size = 3 + pageObjects.size * 3
        val xref = written
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets.getValue(n)))
        sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        raw(sb.toString())
        out.flush()
    }
}
