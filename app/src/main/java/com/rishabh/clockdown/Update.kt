package com.rishabh.clockdown

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Where new versions are published: the GitHub releases of this repository. */
const val UPDATE_REPO = "nerdsanimein-ux/clockdown"

/** A published release. [versionCode] is the release tag (see the comment in app/build.gradle.kts). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

/** Turns GitHub's "latest release" JSON into an [UpdateInfo]; null if it isn't a usable release. */
fun parseRelease(json: String): UpdateInfo? {
    val o = try { JSONObject(json) } catch (e: Exception) { return null }
    val code = o.optString("tag_name").trim().removePrefix("v").removePrefix("V").toIntOrNull() ?: return null
    val assets = o.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
    val label = o.optString("name").ifBlank { o.optString("tag_name") }
        .trim().removePrefix("Clockdown").trim().removePrefix("v").removePrefix("V")
    return UpdateInfo(
        versionCode = code,
        versionName = label.ifBlank { code.toString() },
        notes = o.optString("body"),
        apkUrl = apk.getString("browser_download_url"),
        apkSize = apk.optLong("size", 0),
        sha256 = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
    )
}

/**
 * Release notes are written as markdown; show them as plain, friendly text. The dialog already has its own
 * "What's new" label, so a leading heading with that name is dropped rather than shown twice.
 */
fun cleanNotes(md: String): String = md.lines().dropWhile { it.isBlank() }.let { lines ->
    val first = lines.firstOrNull()?.trim()?.trimStart('#')?.trim()
    if (first.equals("what's new", ignoreCase = true)) lines.drop(1) else lines
}.joinToString("\n") { line ->
    val t = line.trim()
    when {
        t.startsWith("#") -> t.trimStart('#').trim()
        t.startsWith("- ") || t.startsWith("* ") -> "• " + t.drop(2).trim()
        else -> t
    }.replace("**", "").replace("`", "")
}.replace(Regex("\n{3,}"), "\n\n").trim()

enum class CheckResult { UPDATE, CURRENT, OFFLINE, LIMITED }

/** How eagerly to ask GitHub. [REVALIDATE] always asks (cheaply, with an ETag) and is used before offering a download. */
enum class CheckMode { AUTO, MANUAL, REVALIDATE }

/** Where the update state is kept. A tiny interface so the logic below can be tested without an Android device. */
interface UpdateStore {
    fun getString(key: String): String?
    fun getLong(key: String): Long
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putLong(key: String, value: Long)
        fun remove(key: String)
    }
}

/**
 * All of the "is there an update?" logic. It never offers a release that can't be downloaded: a release that has
 * disappeared (404) or isn't newer than what's installed is forgotten immediately, not left in the cache.
 */
class UpdateEngine(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val store: UpdateStore,
    private val installedCode: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun cached(): UpdateInfo? = store.getString("updRelease")?.let(::parseRelease)

    /** The update to offer: the cached release, only if it is newer than what's installed. No network. */
    fun available(): UpdateInfo? = cached()?.takeIf { it.versionCode > installedCode() }

    fun lastChecked() = store.getLong("updCheckedAt")

    /** Drop everything we believe about releases, e.g. when the download shows the release no longer exists. */
    fun forget() = store.edit { remove("updRelease"); remove("updEtag") }

    private fun result() = if (available() != null) CheckResult.UPDATE else CheckResult.CURRENT

    /**
     * Blocking; call off the main thread. Answers within the freshness window come from the cache; otherwise one request
     * is made, conditional on the ETag, which GitHub doesn't count against its anonymous limit of 60 requests an hour.
     */
    @Synchronized
    fun check(mode: CheckMode): CheckResult {
        // A cached release that isn't newer than the installed version (e.g. we just updated to it) is stale.
        if (store.getString("updRelease") != null && available() == null) forget()

        val window = when (mode) { CheckMode.AUTO -> AUTO_FRESH_MS; CheckMode.MANUAL -> MANUAL_FRESH_MS; CheckMode.REVALIDATE -> 0L }
        if (window > 0 && now() - lastChecked() < window) return result()

        val req = Request.Builder()
            .url("$baseUrl/repos/$UPDATE_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Clockdown-Updater")
        if (store.getString("updRelease") != null) store.getString("updEtag")?.let { req.header("If-None-Match", it) }

        try {
            http.newCall(req.build()).execute().use { r ->
                when (r.code) {
                    304 -> Unit // unchanged: keep what we have
                    200 -> {
                        val body = r.body.string().take(200_000)
                        val info = parseRelease(body)
                        if (info == null || info.versionCode <= installedCode()) forget()
                        else store.edit { putString("updRelease", body); putString("updEtag", r.header("ETag")) }
                    }
                    404 -> forget() // nothing is published (any more)
                    403, 429 -> { // asked too often: wait out the normal window instead of retrying
                        store.edit { putLong("updCheckedAt", now()) }
                        return CheckResult.LIMITED
                    }
                    else -> return CheckResult.OFFLINE
                }
            }
        } catch (e: IOException) {
            return CheckResult.OFFLINE
        }
        store.edit { putLong("updCheckedAt", now()) }
        return result()
    }

    companion object {
        const val AUTO_FRESH_MS = 12 * 60 * 60 * 1000L // background checks reuse a result younger than this
        const val MANUAL_FRESH_MS = 60 * 1000L         // "Check for updates" never asks GitHub more than once a minute
    }
}

private class PrefsUpdateStore(private val p: SharedPreferences) : UpdateStore {
    override fun getString(key: String) = p.getString(key, null)
    override fun getLong(key: String) = p.getLong(key, 0)
    override fun edit(block: UpdateStore.Editor.() -> Unit) {
        val e = p.edit()
        object : UpdateStore.Editor {
            override fun putString(key: String, value: String?) { e.putString(key, value) }
            override fun putLong(key: String, value: Long) { e.putLong(key, value) }
            override fun remove(key: String) { e.remove(key) }
        }.block()
        e.apply()
    }
}

object UpdateChecker {
    private val client = OkHttpClient()

    private fun code(pi: PackageInfo) = PackageInfoCompat.getLongVersionCode(pi).toInt()
    fun installedCode(ctx: Context) = code(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun engine(ctx: Context) = UpdateEngine(client, "https://api.github.com", PrefsUpdateStore(ctx.prefs), installedCode = { installedCode(ctx) })

    fun available(ctx: Context): UpdateInfo? = engine(ctx).available()
    fun lastChecked(ctx: Context) = engine(ctx).lastChecked()
    fun forget(ctx: Context) = engine(ctx).forget()
    fun check(ctx: Context, mode: CheckMode): CheckResult = engine(ctx).check(mode)

    /** Once a day, when there's a connection. The result is only cached; the Settings badge reads the cache. */
    fun scheduleDaily(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("updates", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        UpdateChecker.check(applicationContext, CheckMode.AUTO)
        return Result.success() // no retry: the next daily run is soon enough
    }
}

sealed interface Download {
    data class Done(val file: File) : Download
    data object NoInternet : Download
    data object Interrupted : Download
    /** The file is gone from GitHub (the release was deleted or replaced), as opposed to a passing network problem. */
    data object Gone : Download
    data object Corrupt : Download
    data object WrongPublisher : Download
    data object Invalid : Download
}

/** The network half of downloading, free of Android APIs so it can be tested. Writes [dest] only on success. */
suspend fun fetchApk(
    http: OkHttpClient, url: String, expectedSize: Long, sha256: String?, dest: File, onProgress: (Float) -> Unit = {},
): Download = withContext(Dispatchers.IO) {
    val part = File(dest.path + ".part")
    try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Clockdown-Updater").build()).execute().use { r ->
            // 404/410 mean the release or file no longer exists; retrying can never help. Anything else non-OK is a server hiccup.
            if (r.code == 404 || r.code == 410) return@withContext Download.Gone
            if (!r.isSuccessful) return@withContext Download.Interrupted
            val total = r.body.contentLength().takeIf { it > 0 } ?: expectedSize
            val digest = MessageDigest.getInstance("SHA-256")
            r.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        coroutineContext.ensureActive() // lets the user cancel mid-download
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0 && done - reported > 128 * 1024) { onProgress(done.toFloat() / total); reported = done }
                    }
                    if (total > 0 && done != total) { part.delete(); return@withContext Download.Interrupted }
                }
            }
            onProgress(1f)
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != null && !sum.equals(sha256, ignoreCase = true)) { part.delete(); return@withContext Download.Corrupt }
        }
    } catch (e: CancellationException) {
        part.delete(); throw e
    } catch (e: UnknownHostException) {
        part.delete(); return@withContext Download.NoInternet
    } catch (e: IOException) {
        part.delete()
        // Offline at the start looks like a connect failure; anything later is the connection dropping mid-way.
        return@withContext if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) Download.NoInternet else Download.Interrupted
    }
    if (!part.renameTo(dest)) Download.Interrupted else Download.Done(dest)
}

object ApkDownloader {
    private val client = OkHttpClient()

    /** Downloads into the app's cache and checks it is really an update for this app before returning it. */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): Download {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads and half-finished ones
        val out = File(dir, "clockdown-${info.versionCode}.apk")
        val r = fetchApk(client, info.apkUrl, info.apkSize, info.sha256, out, onProgress)
        if (r !is Download.Done) return r
        return verify(ctx, out).let { if (it == null) r else { out.delete(); it } }
    }

    private fun signers(pi: PackageInfo?): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) pi?.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") pi?.signatures
        return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Null if fine. Android would refuse a wrong package or a different publisher anyway, but we can say so clearly. */
    private fun verify(ctx: Context, apk: File): Download? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return Download.Invalid
        if (archive.packageName != ctx.packageName || code(archive) <= UpdateChecker.installedCode(ctx)) return Download.Invalid
        if (signers(archive) != signers(pm.getPackageInfo(ctx.packageName, flags))) return Download.WrongPublisher
        return null
    }

    private fun code(pi: PackageInfo) = PackageInfoCompat.getLongVersionCode(pi).toInt()
}

object ApkInstaller {
    /** Android asks each app for permission before it may install other packages, including its own updates. */
    fun allowed(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun permissionScreen(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the downloaded file to Android's installer through a content:// address (file:// is not allowed any more). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
