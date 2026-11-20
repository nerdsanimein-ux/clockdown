package com.rishabh.clockdown

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "📷 Class photo" on the class that's on now: the camera, then the photo goes into Pictures/Clockdown/<class name>, its
 * own album in the gallery. Only photos taken with this button: the person's other photos are never touched, and no
 * photo or camera permission is needed. Android 10+ only: older versions need the storage permission to add to Pictures.
 */
object ClassPhotos {
    val supported get() = Build.VERSION.SDK_INT >= 29 // ponytail: Android 8-9 get no button; add WRITE_EXTERNAL_STORAGE (maxSdk 28) if students ask

    fun folderName(e: Event) = e.title().replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Class" }.take(60)

    /** Copies the camera's photo into the class's folder. */
    @RequiresApi(29)
    fun save(ctx: Context, src: Uri, e: Event, takenAt: Long): Boolean {
        val name = "${folderName(e)} ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss").format(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()))}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Clockdown/${folderName(e)}")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from the gallery until it's fully written
        }
        val cr = ctx.contentResolver
        val dest = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            cr.openInputStream(src)!!.use { i -> cr.openOutputStream(dest)!!.use { i.copyTo(it) } }
            cr.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (x: Exception) { cr.delete(dest, null, null); false }
    }
}

@Composable
fun ClassPhotoButton(e: Event) {
    if (!ClassPhotos.supported) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) } // survives the screen being recreated while the camera is open
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let(::File) ?: return@rememberLauncherForActivityResult
        pending = null
        if (!ok) { f.delete(); return@rememberLauncherForActivityResult }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { ClassPhotos.save(ctx, Uri.fromFile(f), e, System.currentTimeMillis()).also { f.delete() } }
            Toast.makeText(ctx, if (saved) "Saved in Pictures/Clockdown/${ClassPhotos.folderName(e)}" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
        }
    }
    TextButton(onClick = {
        val f = File(File(ctx.cacheDir, "scan-pages").apply { mkdirs() }, "class-${System.nanoTime()}.jpg") // a folder the camera may write to (see file_paths)
        pending = f.path
        take.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }) { Text("📷 Class photo") }
}
