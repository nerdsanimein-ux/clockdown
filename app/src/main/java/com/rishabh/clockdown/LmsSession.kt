package com.rishabh.clockdown

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebSettings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * The LMS (Moodle) sign-in: its address (the semester's folder, as the Amizone Home page gave it) and the MoodleSession
 * cookie, encrypted at rest with a Keystore key, in a file of their own, apart from the Amizone cookies. Never logged or shown.
 */
object LmsSession {
    private const val FILE = "secure_lms"
    @Volatile private var store: SharedPreferences? = null

    private fun open(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        fun create() = EncryptedSharedPreferences.create(
            app, FILE, MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try { create() } catch (e: Exception) { app.deleteSharedPreferences(FILE); create() } // unreadable (restored elsewhere): start clean
    }

    private fun sp(ctx: Context): SharedPreferences? = store ?: synchronized(this) { store ?: try { open(ctx).also { store = it } } catch (e: Exception) { null } }

    /** "https://alms06.amizone.net/moodle_26odd" (no trailing slash), or null when not signed in to the LMS. */
    fun base(ctx: Context): String? = try { sp(ctx)?.getString("base", null) } catch (e: Exception) { null }
    fun cookie(ctx: Context): String? = try { sp(ctx)?.getString("cookie", null) } catch (e: Exception) { null }
    fun label(ctx: Context): String = try { sp(ctx)?.getString("label", null) } catch (e: Exception) { null } ?: ""

    fun save(ctx: Context, base: String, cookie: String, label: String) {
        sp(ctx)?.edit()?.putString("base", base)?.putString("cookie", cookie)?.putString("label", label)?.apply()
    }

    /** Moodle may send a fresh cookie with any reply; keep it, as Session.renew does for Amizone. */
    fun renew(ctx: Context, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val old = cookie(ctx) ?: return
        val merged = CookieMerge.merge(old, setCookies, System.currentTimeMillis())
        if (merged != old) sp(ctx)?.edit()?.putString("cookie", merged)?.apply()
    }

    fun clear(ctx: Context) { sp(ctx)?.edit()?.clear()?.apply() }
}

enum class LmsResult(val message: String) {
    OK("LMS updated"),
    NO_SESSION("Not signed in to Amizone"),
    EXPIRED("Amizone sign-in needed"),
    NO_LMS("No LMS found for this Amizone account"),
    FAILED("Couldn't reach the LMS"),
    UNREADABLE("LMS couldn't be read right now"),
}

/** Ends a sync or an action early with an outcome. [kind] is a short label for the failure, never content. */
class LmsStop(val result: LmsResult, val kind: String) : Exception(kind)

internal class LmsReply(val code: Int, val contentType: String?, val location: String?, val body: String, val setCookies: List<String>)

/** One shared HTTP client: no automatic redirects (a redirect to the login page is how an expired session shows up). */
internal object LmsHttp {
    val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    private fun run(b: Request.Builder, cookie: String?, ua: String, headers: Map<String, String>): LmsReply {
        b.header("User-Agent", ua)
        if (cookie != null) b.header("Cookie", cookie)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { LmsReply(it.code, it.header("Content-Type"), it.header("Location"), it.body.string(), it.headers("Set-Cookie")) }
    }

    fun get(url: String, cookie: String?, ua: String, headers: Map<String, String> = emptyMap()) =
        run(Request.Builder().url(url).header("Accept", "text/html,*/*;q=0.8"), cookie, ua, headers)

    fun post(url: String, body: RequestBody, cookie: String?, ua: String, headers: Map<String, String> = emptyMap()) =
        run(Request.Builder().url(url).post(body), cookie, ua, headers)
}

/** What a Moodle reply means for the session. Pure, so every shape of reply can be tested. */
internal fun lmsVerdict(r: LmsReply, html: Boolean = true): Verdict {
    if (r.code in 300..399) return if (r.location.orEmpty().contains("/login/")) Verdict.EXPIRED else Verdict.PROBLEM
    if (r.code == 401) return Verdict.EXPIRED
    if (r.code !in 200..299) return Verdict.PROBLEM
    if (html && (LmsParse.isLoginPage(r.body) || LmsParse.userId(r.body) == 0)) return Verdict.EXPIRED
    return Verdict.OK
}

sealed interface SsoResult {
    class Ok(val base: String, val cookie: String, val label: String) : SsoResult
    object NoSession : SsoResult
    object AmizoneExpired : SsoResult
    object NoForm : SsoResult
    class Failed(val kind: String) : SsoResult
}

/**
 * Single sign-on from Amizone: the Home page has one hidden form per semester, and posting the current one to the LMS gives a
 * MoodleSession cookie, exactly what tapping the button does in a browser. Nothing here is logged; the form's values never
 * leave this function except in that one POST to the amizone.net address the form itself names.
 */
object LmsSso {
    private const val MAX_HOPS = 6

    fun signOn(ctx: Context): SsoResult {
        val amizone = Session.cookies(ctx) ?: return SsoResult.NoSession
        val ua = Session.userAgent(ctx) ?: WebSettings.getDefaultUserAgent(ctx)
        val home = try { AmizoneSync.fetch("/Home", emptyMap(), amizone, ua, xhr = false) } catch (e: IOException) { return SsoResult.Failed("network") }
        when (home.verdict("submit.php")) {
            Verdict.EXPIRED -> { AmizoneSync.expired(ctx); return SsoResult.AmizoneExpired }
            Verdict.PROBLEM -> return SsoResult.Failed("amizone-http")
            Verdict.OK -> Session.renew(ctx, home.setCookies)
            null -> return SsoResult.NoForm // a normal Home page without an LMS card
        }
        val form = LmsParse.currentSemester(LmsParse.ssoForms(home.body)) ?: return SsoResult.NoForm
        return postForm(LmsHttp.client, form, ua, System.currentTimeMillis(), LmsParse::ssoBase)
    }

    /**
     * Posts [form] and follows the redirects by hand (only ever to the same https host), collecting the cookies. Succeeds
     * only if the page it lands on is a signed-in Moodle page. [baseOf] decides where a form may post to; [secure] = false is for tests
     * against a local fake server only.
     */
    internal fun postForm(client: OkHttpClient, form: SsoForm, ua: String, now: Long, baseOf: (String) -> String?, secure: Boolean = true): SsoResult {
        val base = baseOf(form.action) ?: return SsoResult.Failed("bad-action")
        val host = URI(base).host
        val body = FormBody.Builder().apply { form.fields.forEach { (n, v) -> add(n, v) } }.build()
        var url = form.action
        var jar = ""
        var post: RequestBody? = body
        try {
            repeat(MAX_HOPS) {
                val headers = if (post != null) mapOf("Referer" to "https://s.amizone.net/Home", "Origin" to "https://s.amizone.net") else emptyMap()
                val r = if (post != null) postWith(client, url, post!!, jar, ua, headers) else getWith(client, url, jar, ua)
                jar = CookieMerge.merge(jar, r.setCookies, now)
                if (r.code in 300..399) {
                    val next = try { URI(url).resolve(r.location ?: return SsoResult.Failed("redirect")) } catch (e: Exception) { return SsoResult.Failed("redirect") }
                    if ((secure && next.scheme != "https") || !next.host.equals(host, true)) return SsoResult.Failed("redirect-away")
                    url = next.toString(); post = null // a redirect after a POST is followed with a GET
                    return@repeat
                }
                if (r.code !in 200..299) return SsoResult.Failed("http-${r.code}")
                val uid = LmsParse.userId(r.body)
                return if (uid != null && uid > 0 && "MoodleSession" in jar) SsoResult.Ok(base, jar, form.label) else SsoResult.Failed("not-signed-in")
            }
        } catch (e: IOException) { return SsoResult.Failed("network") }
        return SsoResult.Failed("too-many-redirects")
    }

    private fun postWith(c: OkHttpClient, url: String, b: RequestBody, jar: String, ua: String, h: Map<String, String>): LmsReply {
        val rb = Request.Builder().url(url).post(b).header("User-Agent", ua)
        if (jar.isNotEmpty()) rb.header("Cookie", jar)
        h.forEach { (k, v) -> rb.header(k, v) }
        return c.newCall(rb.build()).execute().use { LmsReply(it.code, it.header("Content-Type"), it.header("Location"), it.body.string(), it.headers("Set-Cookie")) }
    }

    private fun getWith(c: OkHttpClient, url: String, jar: String, ua: String): LmsReply {
        val rb = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "text/html,*/*;q=0.8")
        if (jar.isNotEmpty()) rb.header("Cookie", jar)
        return c.newCall(rb.build()).execute().use { LmsReply(it.code, it.header("Content-Type"), it.header("Location"), it.body.string(), it.headers("Set-Cookie")) }
    }
}

/**
 * Talks to the LMS with the saved session. If the session turns out to be dead it signs on again ONCE (a fresh single sign-on
 * from the Amizone session) and repeats the request; a second failure ends the work. It never loops.
 */
class LmsClient(private val ctx: Context) {
    private val ua = Session.userAgent(ctx) ?: WebSettings.getDefaultUserAgent(ctx)
    private var base: String
    private var cookie: String
    private var reSignedOn = false
    private var sesskey: String? = null

    init {
        val b = LmsSession.base(ctx)
        val c = LmsSession.cookie(ctx)
        if (b != null && c != null) { base = b; cookie = c } else { base = ""; cookie = ""; signOn() }
    }

    val root get() = base

    /** The ONE re-sign-on a client may do. */
    private fun signOn() {
        if (reSignedOn) throw LmsStop(LmsResult.FAILED, "session")
        reSignedOn = true
        when (val r = LmsSso.signOn(ctx)) {
            is SsoResult.Ok -> { base = r.base; cookie = r.cookie; sesskey = null; LmsSession.save(ctx, r.base, r.cookie, r.label) }
            SsoResult.NoSession -> throw LmsStop(LmsResult.NO_SESSION, "no-session")
            SsoResult.AmizoneExpired -> throw LmsStop(LmsResult.EXPIRED, "amizone-expired")
            SsoResult.NoForm -> throw LmsStop(LmsResult.NO_LMS, "no-lms")
            is SsoResult.Failed -> throw LmsStop(LmsResult.FAILED, "sso-" + r.kind)
        }
    }

    private fun send(html: Boolean, call: () -> LmsReply): LmsReply {
        var r = call()
        if (lmsVerdict(r, html) == Verdict.EXPIRED || (!html && LmsParse.ajaxSaysSignedOut(r.body))) {
            signOn(); r = call()
            if (lmsVerdict(r, html) == Verdict.EXPIRED || (!html && LmsParse.ajaxSaysSignedOut(r.body))) throw LmsStop(LmsResult.FAILED, "session")
        }
        if (lmsVerdict(r, html) == Verdict.PROBLEM) throw LmsStop(LmsResult.FAILED, "http-${r.code}")
        if (r.setCookies.isNotEmpty()) { LmsSession.renew(ctx, r.setCookies); cookie = LmsSession.cookie(ctx) ?: cookie }
        return r
    }

    /** A page's HTML, [path] relative to the semester's folder ("course/view.php?id=5"). */
    fun page(path: String): String = send(true) { LmsHttp.get("$base/$path", cookie, ua) }.body.also { if (sesskey == null) sesskey = LmsParse.sesskey(it) }

    /** One call of Moodle's own AJAX service (what its pages use); returns the raw JSON text. */
    fun ajax(method: String, argsJson: String): String {
        if (sesskey == null) page("my/courses.php")
        return send(false) {
            LmsHttp.post(
                "$base/lib/ajax/service.php?sesskey=${sesskey ?: ""}&info=$method",
                "[{\"index\":0,\"methodname\":\"$method\",\"args\":$argsJson}]".toRequestBody("application/json".toMediaType()),
                cookie, ua, mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
            )
        }.body
    }

    /** Same session, for a file download: the caller gets the cookie and agent to use on the amizone.net address it is following. */
    internal fun credentials(): Triple<String, String, String> = Triple(base, cookie, ua)

    /** Re-signs on (once) if a download found the session dead. */
    internal fun refreshSession(): Triple<String, String, String> { signOn(); return credentials() }
}

