package com.rishabh.clockdown

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val SITE = "https://s.amizone.net"
private const val EXPIRED_ID = 1_000_000 // event notifications use +id / -id, so this can't collide
private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd hh:mm:ss a", Locale.US)

// Stored in app-private prefs, and excluded from backups (see res/xml/backup_rules.xml).
// Keys: cookies, ua, lastSync, lastJson, classAlarm, classMinutes.
val Context.prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
val Context.amizoneConnected get() = prefs.contains("cookies")

/** Lead time for class alarms; 0 when the user turned class alarms off. */
val Context.classAlarmMinutes get() = if (prefs.getBoolean("classAlarm", true)) prefs.getInt("classMinutes", 10) else 0

enum class SyncResult(val message: String) {
    OK("Synced"),
    NO_SESSION("Not connected to Amizone"),
    EXPIRED("Amizone session expired, log in again"),
    FAILED("Sync failed (network, server or format change)"),
}

private fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).trim().ifBlank { null }

/** Turns the GetDiaryEvents array into events sorted by start time. Throws on any malformed row. */
fun parseClasses(arr: JSONArray): List<Event> = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
    val title = o.getString("title")
    val code = o.str("CourseCode") ?: title.substringBefore(" - ")
    fun millis(key: String) = LocalDateTime.parse(o.getString(key), TIME).atZone(IST).toInstant().toEpochMilli()
    Event(
        name = title.substringAfter(" - ", title).replace(Regex("\\s+"), " ").trim(),
        startMillis = millis("start"),
        endMillis = millis("end"),
        alarmMinutes = 0,
        source = AMIZONE,
        amizoneId = o.getLong("id"),
        courseCode = code,
        // FacultyName is HTML like "<b>Dr X (12345)</b> </br>Group/Sec - ...": keep just the name.
        faculty = o.str("FacultyName")?.let { Regex("<b>(.*?)</b>").find(it)?.groupValues?.get(1) }
            ?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")?.trim()?.ifBlank { null },
        room = o.str("RoomNo"),
    )
}.sortedBy { it.startMillis }

object AmizoneSync {
    // No redirects: a 302 to the login page is one of the ways an expired session shows up.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    /** Blocking; call off the main thread. Synchronized so the worker and the UI never sync at once. */
    @Synchronized
    fun run(ctx: Context): SyncResult {
        val p = ctx.prefs
        val cookies = p.getString("cookies", null) ?: return SyncResult.NO_SESSION
        val ua = p.getString("ua", null) ?: WebSettings.getDefaultUserAgent(ctx)

        // The endpoint ignores `end` and returns only the `start` day, so fetch each day separately.
        // All days must succeed before anything is changed, so a failure never leaves a half-updated timetable.
        val today = LocalDate.now(IST)
        val rows = mutableListOf<JSONArray>()
        val log = StringBuilder() // shown in the debug viewer
        for (day in (0L..7L).map { today.plusDays(it) }) {
            val (code, body) = try { fetchDay(day, cookies, ua) } catch (e: IOException) { return SyncResult.FAILED }
            log.append("== $day  HTTP $code\n${body.take(30_000)}\n\n")
            if (code >= 500) return SyncResult.FAILED
            val arr = try { JSONArray(body) } catch (e: JSONException) { p.edit().putString("lastJson", log.toString()).apply(); return expired(ctx) } // HTML/login page/401/302
            if (code !in 200..299) { p.edit().putString("lastJson", log.toString()).apply(); return expired(ctx) }
            rows += arr
        }
        p.edit().putString("lastJson", log.toString().take(200_000)).apply()
        val end = today.plusDays(8)

        val now = System.currentTimeMillis()
        val endMs = end.atStartOfDay(IST).toInstant().toEpochMilli()
        // A row we can't parse must not wipe the timetable: keep old events and report failure.
        val fresh = try { rows.flatMap { parseClasses(it) } } catch (e: Exception) { return SyncResult.FAILED }
            .distinctBy { it.amizoneId }
            .filter { it.startMillis in (now + 1) until endMs }
            .sortedBy { it.startMillis }

        AppDb.get(ctx).replaceAmizone(now, endMs, fresh).forEach { Scheduler.cancel(ctx, it) }
        Scheduler.rescheduleAll(ctx)
        p.edit().putLong("lastSync", now).apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(EXPIRED_ID)
        return SyncResult.OK
    }

    private fun fetchDay(day: LocalDate, cookies: String, ua: String): Pair<Int, String> {
        val url = "$SITE/Calendar/home/GetDiaryEvents".toHttpUrl().newBuilder()
            .addQueryParameter("start", day.toString()).addQueryParameter("end", day.plusDays(1).toString()).build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$SITE/Home")
            .header("User-Agent", ua)
            .header("Cookie", cookies)
            .build()
        return client.newCall(req).execute().use { it.code to it.body.string() }
    }

    private fun expired(ctx: Context): SyncResult {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Amizone sync", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(EXPIRED_ID, Notification.Builder(ctx, "sync")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Amizone session expired").setContentText("Tap to log in again")
            .setContentIntent(PendingIntent.getActivity(ctx, 1, Intent(ctx, LoginActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).setOnlyAlertOnce(true).build())
        return SyncResult.EXPIRED
    }

    /** Forget the session and drop all class events. Call off the main thread (the cookie wipe is done by the caller). */
    fun disconnect(ctx: Context) {
        ctx.prefs.edit().remove("cookies").remove("ua").remove("lastSync").remove("lastJson").apply()
        WorkManager.getInstance(ctx).cancelUniqueWork("amizone")
        ctx.getSystemService(NotificationManager::class.java).cancel(EXPIRED_ID)
        AppDb.get(ctx).deleteAmizone().forEach { Scheduler.cancel(ctx, it) }
        Scheduler.rescheduleAll(ctx)
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("amizone", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result =
        if (AmizoneSync.run(applicationContext) == SyncResult.FAILED) Result.retry() else Result.success()
}

/** "Connect Amizone": you log in yourself; only the resulting session cookies are kept, never the password. */
class LoginActivity : ComponentActivity() {
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val u = Uri.parse(url)
                if (done || u.host != "s.amizone.net" || !u.path.orEmpty().trimEnd('/').equals("/Home", ignoreCase = true)) return
                val cookies = CookieManager.getInstance().getCookie(SITE) ?: return
                done = true
                prefs.edit().putString("cookies", cookies).putString("ua", view.settings.userAgentString)
                    .putLong("lastSync", 0).apply() // lastSync=0 makes MainActivity sync as soon as it resumes
                AmizoneSync.schedulePeriodic(applicationContext)
                startActivity(Intent(this@LoginActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
        setContentView(web)
        web.loadUrl(SITE)
    }
}
