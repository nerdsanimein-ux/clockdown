package com.rishabh.clockdown

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.webkit.CookieManager
import android.widget.Toast
import android.widget.TextView
import android.widget.LinearLayout
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
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
private const val SIGN_IN_ID = 1_000_000 // event notifications use +id / -id, so this can't collide
private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd hh:mm:ss a", Locale.US)

// Ordinary app-private preferences (excluded from backups): settings and sync bookkeeping, never credentials.
// The session itself lives in Session (encrypted). Keys here: lastSync (the last CONFIRMED sync), unconfirmed,
// sessionExpired, lastJson (debug view), classAlarm, classMinutes, and the update-check state.
val Context.prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)
val Context.amizoneConnected get() = Session.active(this)

/** True once a sync has found the session dead: the user has to sign in again. */
val Context.sessionExpired get() = prefs.getBoolean("sessionExpired", false)

/** Why the most recent sync could not confirm the timetable (a short label, never any content), for diagnosing "Unconfirmed". */
val Context.lastFailure: String? get() = prefs.getString("lastFailure", null)

/** When the timetable on screen was last confirmed, if it currently can't be (a sync failed); null when it is up to date. 0 = never. */
val Context.unconfirmedSince: Long? get() =
    if (shouldWarnUnconfirmed(amizoneConnected, sessionExpired, prefs.getBoolean("unconfirmed", false), prefs.getLong("lastSync", 0), System.currentTimeMillis())) prefs.getLong("lastSync", 0) else null

/** How old the last confirmed timetable may get before a failed refresh is worth a warning. One blip shouldn't cry wolf. */
const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L

/**
 * The "Unconfirmed" warning: always when the sign-in has expired, but for a plain failed refresh (no network, server
 * hiccup) only once the last confirmed timetable is a few hours old. A timetable confirmed ten minutes ago isn't in doubt.
 */
fun shouldWarnUnconfirmed(connected: Boolean, expired: Boolean, flagged: Boolean, lastSync: Long, now: Long): Boolean =
    connected && flagged && (expired || lastSync == 0L || now - lastSync > STALE_AFTER_MS)

/** Lead time for class alarms; 0 when the user turned class alarms off. */
val Context.classAlarmMinutes get() = if (prefs.getBoolean("classAlarm", true)) prefs.getInt("classMinutes", 10) else 0

enum class SyncResult(val message: String) {
    OK("Synced"),
    NO_SESSION("Not signed in to Amizone"),
    EXPIRED("Amizone sign-in needed"),
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

/** What one request to the timetable endpoint returned. Cookies and headers are deliberately not kept. */
private class Reply(val code: Int, val contentType: String?, val body: String)

object AmizoneSync {
    // No redirects: a 302 to the login page is one of the ways an expired session shows up (see SessionCheck).
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    /**
     * Blocking; call off the main thread. Synchronized so the worker and the UI never sync at once.
     *
     * A failed sync never touches the stored timetable or its alarms: what was last confirmed keeps showing, marked
     * "unconfirmed", and every alarm already scheduled still fires. [force] is for an explicit "Sync now": otherwise a
     * session already known to be dead is not retried until the user signs in again.
     */
    @Synchronized
    fun run(ctx: Context, force: Boolean = false): SyncResult {
        val p = ctx.prefs
        val cookies = Session.cookies(ctx) ?: return SyncResult.NO_SESSION
        if (!force && ctx.sessionExpired) return SyncResult.EXPIRED
        val ua = Session.userAgent(ctx) ?: WebSettings.getDefaultUserAgent(ctx)

        // The endpoint ignores `end` and returns only the `start` day, so fetch each day separately.
        // All days must succeed before anything is changed, so a failure never leaves a half-updated timetable.
        val today = LocalDate.now(IST)
        val rows = mutableListOf<JSONArray>()
        val log = StringBuilder() // shown in the debug viewer: status and body only, never cookies
        for (day in (0L..7L).map { today.plusDays(it) }) {
            val r = try { fetchDay(day, cookies, ua) } catch (e: IOException) { return failed(ctx, "no connection (${e.javaClass.simpleName})") }
            log.append("== $day  HTTP ${r.code}\n${r.body.take(30_000)}\n\n")
            when (SessionCheck.classify(r.code, r.contentType, r.body)) {
                Verdict.OK -> rows += try { JSONArray(SessionCheck.trimBody(r.body)) } catch (e: JSONException) { return failed(ctx, "unreadable reply for $day") }
                Verdict.EXPIRED -> { p.edit().putString("lastJson", log.toString()).apply(); return expired(ctx) }
                Verdict.PROBLEM -> { p.edit().putString("lastJson", log.toString()).apply(); return failed(ctx, "unexpected reply HTTP ${r.code} for $day") }
            }
        }
        p.edit().putString("lastJson", log.toString().take(200_000)).apply()
        val end = today.plusDays(8)

        val now = System.currentTimeMillis()
        val endMs = end.atStartOfDay(IST).toInstant().toEpochMilli()
        // A row we can't parse must not wipe the timetable: keep old events and report failure.
        val fresh = try { rows.flatMap { parseClasses(it) } } catch (e: Exception) { return failed(ctx, "could not read the timetable") }
            .distinctBy { it.amizoneId }
            .filter { it.startMillis in (now + 1) until endMs }
            .sortedBy { it.startMillis }

        AppDb.get(ctx).replaceAmizone(now, endMs, fresh).forEach { Scheduler.cancel(ctx, it) }
        p.edit().putLong("lastSync", now).putBoolean("unconfirmed", false).putBoolean("sessionExpired", false).remove("lastFailure").apply()
        Scheduler.rescheduleAll(ctx) // also refreshes the widgets, which drop their "Unconfirmed" note
        ctx.getSystemService(NotificationManager::class.java).cancel(SIGN_IN_ID)
        return SyncResult.OK
    }

    private fun fetchDay(day: LocalDate, cookies: String, ua: String): Reply {
        val url = "$SITE/Calendar/home/GetDiaryEvents".toHttpUrl().newBuilder()
            .addQueryParameter("start", day.toString()).addQueryParameter("end", day.plusDays(1).toString()).build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$SITE/Home")
            .header("User-Agent", ua)
            .header("Cookie", cookies)
            .build()
        return client.newCall(req).execute().use { Reply(it.code, it.header("Content-Type"), it.body.string()) }
    }

    /** The timetable on screen is the last confirmed one; say so, in the app and on the widgets. */
    private fun markUnconfirmed(ctx: Context) {
        ctx.prefs.edit().putBoolean("unconfirmed", true).apply()
        Scheduler.refreshWidget(ctx)
    }

    private fun failed(ctx: Context, why: String): SyncResult {
        ctx.prefs.edit().putString("lastFailure", "${java.time.LocalTime.now(IST).withNano(0)} $why").apply()
        if (!why.startsWith("no connection")) CrashReporting.note("sync failed: $why") // a dropped connection is normal; anything else is worth knowing
        markUnconfirmed(ctx)
        // Don't wait for the next 6-hourly check: try again in 15 minutes, as soon as there is a connection.
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "amizone-retry", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
        return SyncResult.FAILED
    }

    private fun expired(ctx: Context): SyncResult {
        ctx.prefs.edit().putBoolean("sessionExpired", true).apply()
        markUnconfirmed(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Amizone sign-in", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(SIGN_IN_ID, Notification.Builder(ctx, "sync")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Amizone sign-in needed").setContentText("Tap to sign in again. Your alarms keep working meanwhile.")
            .setContentIntent(PendingIntent.getActivity(ctx, 1, Intent(ctx, LoginActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).setOnlyAlertOnce(true).build())
        return SyncResult.EXPIRED
    }

    /**
     * Forget the session and every trace of the account: cookies, the saved timetable and its alarms, the raw-response
     * log. Call off the main thread; the caller clears the WebView's own cookies and cache (see [wipeWebView]).
     */
    fun signOut(ctx: Context) {
        Session.clear(ctx)
        ctx.prefs.edit().remove("lastSync").remove("lastJson").remove("unconfirmed").remove("sessionExpired").apply()
        WorkManager.getInstance(ctx).cancelUniqueWork("amizone")
        ctx.getSystemService(NotificationManager::class.java).cancel(SIGN_IN_ID)
        AppDb.get(ctx).deleteAmizone().forEach { Scheduler.cancel(ctx, it) }
        Scheduler.rescheduleAll(ctx)
    }

    /** Main thread only. Clears the browser's cookies, storage, cache and history so nothing of the login survives. */
    fun wipeWebView(ctx: Context) {
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        WebView(ctx).apply { clearCache(true); clearHistory(); clearFormData(); destroy() }
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

/**
 * "Sign in to Amizone": the real login page, in a WebView.
 *
 * The user solves the captcha (and, the first time, types their details) in the page itself. Two things happen on top:
 *  - After a SUCCESSFUL sign-in the ID and password are saved, encrypted (see [CredentialStore]), so next time they are
 *    filled in for you. Details from a failed attempt are never saved.
 *  - If saved details exist, the page's own Login button is pressed for you, once, and only when the page itself says
 *    the form is ready: Turnstile has issued its token AND the page has seen a real touch (its own bot check, which we
 *    do not fake). If anything goes wrong we stop and show the form. It can never retry (see [AutoLogin]).
 * Nothing is ever logged, and the captcha token is never read, copied, replayed or solved by us.
 */
class LoginActivity : ComponentActivity() {
    private var done = false
    private var failed = false
    private var prefilled = false
    private var pollsLeft = MAX_POLLS
    private var typedId = ""
    private var typedPassword = ""
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var auto: AutoLogin
    private val handler = Handler(Looper.getMainLooper())

    private fun say(text: String?) { status.text = text ?: ""; status.visibility = if (text == null) View.GONE else View.VISIBLE }

    @SuppressLint("SetJavaScriptEnabled") // the login page and its captcha don't work without it
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val now = System.currentTimeMillis()
        val recentlyTried = now - prefs.getLong("autoLoginAt", 0) < COOL_DOWN_MS // e.g. the screen was rotated mid-attempt
        val saved = CredentialStore.status(this)
        auto = AutoLogin(saved == CredentialStore.Status.SAVED, saved == CredentialStore.Status.REJECTED, recentlyTried)

        fun label(size: Float, pad: Int) = TextView(this).apply {
            textSize = size; setPadding(pad, pad / 2, pad, pad / 2); setTextColor(0xFF1B1B1F.toInt()); setBackgroundColor(0xFFF1EFF7.toInt())
        }
        val dp = resources.displayMetrics.density
        val note = label(12.5f, (14 * dp).toInt()).apply {
            text = "Your Amizone password is stored encrypted on this phone so signing in again is quicker. It is never sent anywhere except Amizone."
        }
        status = label(13.5f, (14 * dp).toInt()).apply { setBackgroundColor(0xFFFFE9C7.toInt()); visibility = View.GONE }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(note, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        web.webChromeClient = object : WebChromeClient() {
            // The page explains itself with alerts ("Please complete the CAPTCHA"); show them instead of swallowing them.
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show(); result.confirm(); return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            // Only the portal and its captcha provider may take over the page: no way to be steered to a look-alike site.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !LoginDetector.allowedPage(request.url.toString())

            override fun onPageFinished(view: WebView, url: String) {
                if (done) return
                val cookies = CookieManager.getInstance().getCookie(SITE)
                if (LoginDetector.signedIn(url, cookies)) { signedIn(view, cookies!!); return }
                if (isLoginPage(url)) {
                    if (auto.failedAfterSubmit()) return failAfterSubmit() // we submitted and Amizone sent us back here
                    startPolling()
                }
            }
        }
        setContentView(root)
        web.loadUrl(SITE)
    }

    private fun isLoginPage(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (e: Exception) { return false }
        return u.host == "s.amizone.net" && u.path.orEmpty().trimEnd('/').isEmpty()
    }

    private fun signedIn(view: WebView, cookies: String) {
        done = true
        handler.removeCallbacksAndMessages(null)
        Session.save(applicationContext, cookies, view.settings.userAgentString)
        // Save the details only now that they are known to be right. Whatever was typed for a failed attempt is dropped.
        if (typedId.isNotBlank() && typedPassword.isNotEmpty()) CredentialStore.save(applicationContext, typedId, typedPassword)
        typedId = ""; typedPassword = ""
        AmizoneSync.schedulePeriodic(applicationContext)
        // The app opens and syncs at once; lastSync is kept so "last confirmed" stays truthful until it succeeds.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Amizone sent us back to the login page after our one automatic attempt: stop for good and let the user type. */
    private fun failAfterSubmit() {
        failed = true
        handler.removeCallbacksAndMessages(null)
        CredentialStore.markRejected(applicationContext)
        say("Your saved login didn't work, so it won't be tried again. Please type your Amizone ID and password. (If you changed your password, this fixes it.)")
    }

    private fun startPolling() {
        handler.removeCallbacksAndMessages(null)
        pollsLeft = MAX_POLLS
        poll()
    }

    private fun poll() {
        if (done || failed || pollsLeft-- <= 0) return
        web.evaluateJavascript(LoginScripts.READ_STATE) { raw ->
            if (done || failed) return@evaluateJavascript
            val state = LoginScripts.parseState(raw, onLoginPage = true)
            if (state != null && state.fieldsFound) onState(state)
            handler.postDelayed({ poll() }, POLL_MS)
        }
    }

    private fun onState(s: LoginPageState) {
        // Remember what is typed (memory only), to save it if the sign-in turns out to succeed.
        if (s.typedId.isNotEmpty() && s.typedPassword.isNotEmpty()) { typedId = s.typedId; typedPassword = s.typedPassword }

        val saved = if (auto.allowed || CredentialStore.status(this) == CredentialStore.Status.SAVED) CredentialStore.get(this) else null
        if (!prefilled && saved != null && s.idEmpty && s.passwordEmpty && !auto.submitted) {
            prefilled = true
            web.evaluateJavascript(LoginScripts.prefill(saved.first, saved.second), null)
            say("Your saved login is filled in. Complete the captcha if it asks, then touch the screen to sign in.")
        }
        if (CredentialStore.status(this) == CredentialStore.Status.REJECTED && !failed && !auto.submitted) {
            say("Your saved login was refused earlier, so it isn't filled in. Please type your Amizone ID and password.")
        }

        if (auto.decide(s) == AutoStep.SUBMIT) {
            auto.markSubmitted() // exactly once per login screen, no matter what happens next
            prefs.edit().putLong("autoLoginAt", System.currentTimeMillis()).apply()
            say("Signing in…")
            web.evaluateJavascript(LoginScripts.CLICK_LOGIN, null)
            // If nothing happens (the page's own check said no), don't leave the user waiting; and never press it again.
            handler.postDelayed({
                if (!done && !failed) say("Couldn't sign in automatically. Please tap Login yourself.")
            }, WATCHDOG_MS)
        }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private companion object {
        const val POLL_MS = 500L
        const val MAX_POLLS = 240              // two minutes of watching the page, then leave it to the user
        const val WATCHDOG_MS = 20_000L
        const val COOL_DOWN_MS = 2 * 60_000L   // a new login screen within this long of an automatic attempt won't auto-submit
    }
}
