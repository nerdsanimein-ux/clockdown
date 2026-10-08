package com.rishabh.clockdown

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Scan pages to PDF": Google's own document scanner (edge detection, crop, several pages, one PDF) when the phone has Google
 * Play services, otherwise the plain camera plus Android's own PdfDocument. Either way the PDF is saved in the app's private
 * "scans" folder and handed back to whoever asked (the upload page's file chooser). Nothing leaves the phone from here.
 */
class ScanActivity : ComponentActivity() {
    private val pages = mutableStateListOf<File>()
    private var fallback by mutableStateOf(false)
    private var offer by mutableStateOf(false) // the scanner closed without a PDF: cancelled, or it could not run
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var pending: File? = null

    private val mlkit = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val pdf = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pdf?.uri else null
        if (pdf == null) offer = true // cancelled, or Google's scanner could not run here: let the student choose, never just vanish
        else finishWith { contentResolver.openInputStream(pdf)?.use { input -> newScanFile().also { f -> f.outputStream().use { input.copyTo(it) } } } }
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { if (ok) pages += it else it.delete() }
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        if (intent.getBooleanExtra(EXTRA_FALLBACK, false)) fallback = true else startScanner()
        setContent { ClockdownTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { if (fallback) Fallback() else if (offer) Offer() else Opening() } } }
    }

    private fun startScanner() {
        try {
            val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { mlkit.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { fallback = true } // no Google Play services, or the scanner isn't available here
        } catch (e: Throwable) { fallback = true }
    }

    private fun newScanFile(): File {
        val dir = File(filesDir, LmsStore.SCANS).apply { mkdirs() }
        return File(dir, "Scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".pdf")
    }

    /** Runs [make] off the main thread; its file (if any) goes back to the caller and the screen closes. */
    private fun finishWith(make: () -> File?) {
        busy = true
        thread {
            val f = try { make() } catch (e: Exception) { null }
            runOnUiThread {
                busy = false
                if (f == null) { message = "Couldn't make the PDF. Please try again." }
                else {
                    setResult(Activity.RESULT_OK, Intent().setData(FileProvider.getUriForFile(this, "$packageName.files", f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    pages.forEach { it.delete() }
                    finish()
                }
            }
        }
    }

    /** Photos to one PDF with Android's own PdfDocument: each page sized to its photo (long side at most A4's 842 points). */
    private fun makePdf(): File? {
        val doc = PdfDocument()
        try {
            pages.toList().forEachIndexed { i, f ->
                val bmp = decode(f) ?: return@forEachIndexed
                val s = minOf(1f, 842f / maxOf(bmp.width, bmp.height))
                val w = (bmp.width * s).toInt().coerceAtLeast(1)
                val h = (bmp.height * s).toInt().coerceAtLeast(1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                doc.finishPage(page)
                bmp.recycle()
            }
            if (doc.pages.isEmpty()) return null
            return newScanFile().also { out -> out.outputStream().use { doc.writeTo(it) } }
        } finally { doc.close() }
    }

    /** A photo at a sensible size (long side about 1600 px) and the right way up. */
    private fun decode(f: File, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turn = when (runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        return if (turn == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn) }, true)
    }

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
            Button(onClick = { offer = false; startScanner() }, Modifier.fillMaxWidth()) { Text("Try the scanner again") }
            OutlinedButton(onClick = { offer = false; fallback = true }, Modifier.fillMaxWidth()) { Text("Use the plain camera instead") }
            TextButton(onClick = { finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Opening() {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Opening the scanner…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Fallback() {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan pages", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Take a photo of each page, then make one PDF. Hold the phone straight above the page, in good light.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pages.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages.toList()) { i, f ->
                    val thumb = remember(f.path, f.length()) { decode(f, 300) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (thumb != null) Image(thumb.asImageBitmap(), contentDescription = "Page ${i + 1}", Modifier.height(120.dp))
                        Text("Page ${i + 1}", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { pages.remove(f); f.delete() }) { Text("Remove") }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.weight(1f))
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("  Making the PDF…") }
            Button(
                onClick = {
                    val dir = File(cacheDir, "scan-pages").apply { mkdirs() }
                    val f = File(dir, "page-${System.currentTimeMillis()}.jpg").also { pending = it }
                    message = null
                    camera.launch(FileProvider.getUriForFile(this@ScanActivity, "$packageName.files", f))
                },
                Modifier.fillMaxWidth(), enabled = !busy,
            ) { Text(if (pages.isEmpty()) "Take a photo" else "Add another page") }
            Button(onClick = { finishWith(::makePdf) }, Modifier.fillMaxWidth(), enabled = pages.isNotEmpty() && !busy) {
                Text("Make PDF (${pages.size} page${if (pages.size == 1) "" else "s"})")
            }
            OutlinedButton(onClick = { pages.forEach { it.delete() }; finish() }, Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    companion object {
        /** Debug builds use this to try the camera fallback on a phone that does have Google Play services. */
        const val EXTRA_FALLBACK = "fallback"
    }
}
