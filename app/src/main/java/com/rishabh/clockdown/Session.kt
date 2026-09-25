package com.rishabh.clockdown

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * The Amizone login session (cookies and the browser identity that goes with them), encrypted at rest with a key that
 * lives in the Android Keystore. Nothing here is ever logged, shown, or written to the debug view.
 */
object Session {
    private const val FILE = "secure_session"
    @Volatile private var store: SharedPreferences? = null

    private fun open(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        fun create() = EncryptedSharedPreferences.create(
            app, FILE, MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val sp = try {
            create()
        } catch (e: Exception) {
            // The file can't be decrypted (restored onto another phone, or the Keystore was reset). The session is
            // worthless then anyway: start clean and the user simply signs in again.
            app.deleteSharedPreferences(FILE)
            create()
        }
        migrateFromPlainStorage(app, sp)
        return sp
    }

    /** Earlier versions kept the cookies in ordinary preferences. Move them across once, then erase the plain copy. */
    private fun migrateFromPlainStorage(ctx: Context, sp: SharedPreferences) {
        val plain = ctx.prefs
        val old = plain.getString("cookies", null) ?: return
        if (!sp.contains("cookies")) sp.edit().putString("cookies", old).putString("ua", plain.getString("ua", null)).apply()
        plain.edit().remove("cookies").remove("ua").apply()
    }

    private fun sp(ctx: Context): SharedPreferences? = store ?: synchronized(this) {
        store ?: try { open(ctx).also { store = it } } catch (e: Exception) { null }
    }

    fun cookies(ctx: Context): String? = try { sp(ctx)?.getString("cookies", null) } catch (e: Exception) { null }
    fun userAgent(ctx: Context): String? = try { sp(ctx)?.getString("ua", null) } catch (e: Exception) { null }
    fun active(ctx: Context) = cookies(ctx) != null

    fun save(ctx: Context, cookies: String, userAgent: String) {
        sp(ctx)?.edit()?.putString("cookies", cookies)?.putString("ua", userAgent)?.apply()
        ctx.prefs.edit().putBoolean("sessionExpired", false).apply()
    }

    fun clear(ctx: Context) {
        sp(ctx)?.edit()?.clear()?.apply()
        ctx.prefs.edit().remove("cookies").remove("ua").apply() // in case an old plain copy is somehow still there
    }
}

/** What the timetable sync's answer means for the session. Pure, so every shape of answer can be unit-tested. */
enum class Verdict {
    /** A proper list of classes: the session works. */
    OK,
    /** Signed out: the portal sent us to (or served) its login page, or refused us. */
    EXPIRED,
    /** The portal or the network had a problem. Says nothing about the session, so don't tell the user to sign in. */
    PROBLEM,
}

object SessionCheck {
    /** Drops a leading byte-order mark and whitespace, which some servers put before the JSON. */
    fun trimBody(body: String) = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')

    /**
     * Signals that mean "signed out", in the order they are checked:
     *  1. HTTP 301/302/303/307/308: when signed in, this endpoint answers directly; a redirect is to the login page.
     *  2. HTTP 401 or 403: refused.
     *  3. A 2xx whose body is the login page (HTML, or containing the login form), which is what an AJAX call gets when
     *     the portal serves the login page instead of redirecting.
     *  4. A 2xx that is not a JSON list of classes at all.
     * 5xx, and 404/410 (the endpoint moved), are [Verdict.PROBLEM]: they say nothing about the session.
     */
    fun classify(code: Int, contentType: String?, body: String): Verdict {
        if (code in 300..399) return Verdict.EXPIRED
        if (code == 401 || code == 403) return Verdict.EXPIRED
        if (code >= 500 || code == 404 || code == 410 || code == 408 || code == 429) return Verdict.PROBLEM
        if (code !in 200..299) return Verdict.PROBLEM
        val text = trimBody(body)
        if (contentType?.contains("html", ignoreCase = true) == true) return Verdict.EXPIRED
        if (text.startsWith("<") || text.contains("id=\"loginform\"") || text.contains("name=\"_UserName\"")) return Verdict.EXPIRED
        return try { JSONArray(text); Verdict.OK } catch (e: Exception) { Verdict.EXPIRED }
    }
}

/** The two facts that mean "the user is now signed in", and which pages the login WebView may show. Pure, so testable. */
object LoginDetector {
    private val portal = "s.amizone.net"

    /**
     * Signed in when BOTH hold:
     *  1. The page that just finished loading is the portal's post-login landing page, /Home. (A wrong password, or
     *     the captcha not yet solved, leaves the browser on the login page at "/", so this stays false.)
     *  2. The portal has issued its authentication cookie, .ASPXAUTH. Only the cookie's NAME is checked here.
     * Nothing is read from the page itself: no form fields, no scripts, no captcha token.
     */
    fun signedIn(url: String?, cookieHeader: String?): Boolean {
        val u = try { java.net.URI(url ?: return false) } catch (e: Exception) { return false }
        if (u.scheme != "https" || u.host != portal) return false
        if (!u.path.orEmpty().trimEnd('/').equals("/Home", ignoreCase = true)) return false
        return cookieHeader.orEmpty().split(';').any { it.trim().startsWith(".ASPXAUTH=") }
    }

    /** https only, and only the portal itself or its captcha provider. */
    fun allowedPage(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (e: Exception) { return false }
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && (host == "amizone.net" || host.endsWith(".amizone.net") || host == "cloudflare.com" || host.endsWith(".cloudflare.com"))
    }
}
