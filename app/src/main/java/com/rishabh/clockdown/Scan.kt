package com.rishabh.clockdown

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.rishabh.clockdown.ui.theme.ClockdownTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Scan pages to PDF". Every scan or photo is added to a page list that Clockdown keeps, so you can scan as often as you like (up
 * to [MAX_PAGES] pages), move or delete pages, and only then make ONE PDF. Pages come from Google's document scanner (edge
 * detection and cleaning; it can take several pages in one go) when the phone has Google Play services, or from the plain camera
 * otherwise. The PDF is saved in the app's private "scans" folder and handed back to the upload page's file chooser.
 * Nothing leaves the phone from here.
 */
class ScanActivity : ComponentActivity() {
    private enum class Mode { OPENING, OFFER, REVIEW }

    private val pages = mutableStateListOf<File>()
    private var mode by mutableStateOf(Mode.OPENING)
    private var camera by mutableStateOf(false) // the scanner can't be used on this phone: add pages with the plain camera
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var pending: File? = null

    private val pageDir get() = File(cacheDir, "scan-pages").apply { mkdirs() }

    private val mlkit = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val got = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }.orEmpty() else emptyList()
        if (got.isEmpty()) { mode = if (pages.isEmpty()) Mode.OFFER else Mode.REVIEW; return@registerForActivityResult } // cancelled, or it couldn't run
        busy = true
        thread {
            val files = got.mapNotNull { uri -> copyPage(uri) }
            runOnUiThread { pages += files.take(MAX_PAGES - pages.size); busy = false; mode = Mode.REVIEW }
        }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { if (ok && pages.size < MAX_PAGES) pages += it else it.delete() }
        pending = null
        mode = Mode.REVIEW
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        if (savedInstanceState != null) {
            // The screen was recreated while the camera or scanner was in front (phones do this when short of memory): carry on where we were.
            savedInstanceState.getStringArrayList("pages")?.map { File(it) }?.filter { it.exists() }?.let { pages += it }
            pending = savedInstanceState.getString("pending")?.let { File(it) }
            camera = savedInstanceState.getBoolean("camera")
            mode = runCatching { Mode.valueOf(savedInstanceState.getString("mode").orEmpty()) }.getOrDefault(Mode.REVIEW)
        } else if (intent.getBooleanExtra(EXTRA_FALLBACK, false)) { camera = true; mode = Mode.REVIEW }
        else startScanner()
        setContent {
            ClockdownTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (mode) { Mode.OPENING -> Opening(); Mode.OFFER -> Offer(); Mode.REVIEW -> Review() }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("pages", ArrayList(pages.map { it.path }))
        outState.putString("pending", pending?.path)
        outState.putBoolean("camera", camera)
        outState.putString("mode", mode.name)
    }

    private fun startScanner() {
        mode = Mode.OPENING
        try {
            val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true)
                .setPageLimit((MAX_PAGES - pages.size).coerceIn(1, 30)) // the scanner itself takes several pages in one go
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { mlkit.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { camera = true; mode = Mode.REVIEW } // no Google Play services, or the scanner isn't available here
        } catch (e: Throwable) { camera = true; mode = Mode.REVIEW }
    }

    private fun takePhoto() {
        val f = File(pageDir, "page-${System.nanoTime()}.jpg").also { pending = it }
        message = null
        takePicture.launch(FileProvider.getUriForFile(this, "$packageName.files", f))
    }

    private fun addPages() { if (camera) takePhoto() else startScanner() }

    /** A scanner page, copied into our own folder (the scanner's copy is temporary). */
    private fun copyPage(uri: Uri): File? = try {
        File(pageDir, "page-${System.nanoTime()}.jpg").also { f -> contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
    } catch (e: Exception) { null }

    private fun newScanFile(): File {
        val dir = File(filesDir, LmsStore.SCANS).apply { mkdirs() }
        return File(dir, "Scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".pdf")
    }

    /** Every page, in the order shown, into ONE PDF; then it goes back to the caller and the screen closes. */
    private fun makePdf() {
        busy = true; message = null
        val list = pages.toList()
        thread {
            val f = try { buildPdf(list) } catch (e: Exception) { null }
            runOnUiThread {
                busy = false
                if (f == null) message = "Couldn't make the PDF. Please try again."
                else {
                    setResult(Activity.RESULT_OK, Intent().setData(FileProvider.getUriForFile(this, "$packageName.files", f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    pages.forEach { it.delete() }
                    finish()
                }
            }
        }
    }

    private fun buildPdf(list: List<File>): File? {
        val out = newScanFile()
        var count = 0
        out.outputStream().buffered().use { stream ->
            val pdf = PdfWriter(stream)
            for (f in list) {
                val bmp = decode(f, PDF_SIDE) ?: continue
                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val (wPt, hPt) = pageSize(bmp.width, bmp.height)
                pdf.addJpegPage(bytes, bmp.width, bmp.height, wPt, hPt)
                bmp.recycle(); count++
            }
            if (count > 0) pdf.finish()
        }
        if (count == 0) { out.delete(); return null }
        return out
    }

    /** A photo at a sensible size (long side about [maxSide] px) and the right way up. */
    private fun decode(f: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turn = when (runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        return if (turn == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true)
    }

    private fun move(i: Int, by: Int) { val j = i + by; if (j in pages.indices) { val f = pages.removeAt(i); pages.add(j, f) } }

    @androidx.compose.runtime.Composable
    private fun Opening() {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Opening the scanner…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** The scanner closed with nothing scanned: cancelled, or it couldn't run. Never just vanish. */
    @androidx.compose.runtime.Composable
    private fun Offer() {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The scanner didn't finish", style = MaterialTheme.typography.headlineMedium)
            Text(
                "If you cancelled it, nothing was saved. If it said something went wrong, Google's scanner may not be ready on this phone yet " +
                    "(it needs Google Play services and a connection the first time). You can try again, or take photos with the plain camera instead.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { startScanner() }, Modifier.fillMaxWidth()) { Text("Try the scanner again") }
            OutlinedButton(onClick = { camera = true; mode = Mode.REVIEW }, Modifier.fillMaxWidth()) { Text("Use the plain camera instead") }
            TextButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    /** The pages so far: reorder, delete, add more, then make ONE PDF. */
    @androidx.compose.runtime.Composable
    private fun Review() {
        val full = pages.size >= MAX_PAGES
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (pages.isEmpty()) "Scan pages" else "${pages.size} page${if (pages.size == 1) "" else "s"}", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (pages.isEmpty()) (if (camera) "Take a photo of each page, then make one PDF. Hold the phone straight above the page, in good light." else "Scan each page, then make one PDF.")
                else "Move pages up or down, remove any you don't want, add more, then make one PDF of all of them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages.toList(), key = { _, f -> f.path }) { i, f ->
                    val thumb = remember(f.path) { decode(f, 320) }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (thumb != null) Image(thumb.asImageBitmap(), contentDescription = "Page ${i + 1}", Modifier.height(96.dp).size(width = 72.dp, height = 96.dp))
                            Text("Page ${i + 1}", Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { move(i, -1) }, enabled = i > 0 && !busy) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move page ${i + 1} up") }
                            IconButton(onClick = { move(i, 1) }, enabled = i < pages.lastIndex && !busy) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move page ${i + 1} down") }
                            IconButton(onClick = { pages.remove(f); f.delete() }, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = "Remove page ${i + 1}") }
                        }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Working…") }
            Button(onClick = { addPages() }, Modifier.fillMaxWidth(), enabled = !busy && !full) {
                Text(if (full) "Page limit reached ($MAX_PAGES)" else if (pages.isEmpty()) (if (camera) "Take a photo" else "Scan a page") else if (camera) "Add another page" else "Scan more pages")
            }
            Button(onClick = { makePdf() }, Modifier.fillMaxWidth(), enabled = pages.isNotEmpty() && !busy) {
                Text("Make PDF (${pages.size} page${if (pages.size == 1) "" else "s"})")
            }
            OutlinedButton(onClick = { pages.forEach { it.delete() }; finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    companion object {
        /** Debug builds use this to try the camera path on a phone that does have Google Play services. */
        const val EXTRA_FALLBACK = "fallback"
        /** The most pages in one PDF. */
        const val MAX_PAGES = 50
        private const val PDF_SIDE = 1600

        /** A page's size in PDF points: its photo scaled so the long side is at most A4's 842, keeping its shape. */
        fun pageSize(w: Int, h: Int): Pair<Int, Int> {
            val s = minOf(1f, 842f / maxOf(w, h))
            return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
        }
    }
}
