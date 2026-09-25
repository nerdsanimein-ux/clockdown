package com.rishabh.clockdown

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Amizone ID and password, saved after a successful sign-in so signing in again is quicker.
 * Kept in its OWN encrypted file (AES-256-GCM, Keystore key), separate from the session, and never logged or shown.
 * Only ever used to fill in Amizone's own login page; nothing is sent anywhere else.
 */
object CredentialStore {
    private const val FILE = "secure_login"
    @Volatile private var store: SharedPreferences? = null

    private fun open(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        fun create() = EncryptedSharedPreferences.create(
            app, FILE, MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try { create() } catch (e: Exception) { app.deleteSharedPreferences(FILE); create() } // unreadable: start clean
    }

    private fun sp(ctx: Context): SharedPreferences? = store ?: synchronized(this) {
        store ?: try { open(ctx).also { store = it } } catch (e: Exception) { null }
    }

    enum class Status { NONE, SAVED, REJECTED }

    fun status(ctx: Context): Status = try {
        val p = sp(ctx)
        when {
            p == null || !p.contains("id") || !p.contains("pw") -> Status.NONE
            p.getBoolean("rejected", false) -> Status.REJECTED
            else -> Status.SAVED
        }
    } catch (e: Exception) { Status.NONE }

    /** The saved pair, or null. [id] and [password] are never logged by anything that calls this. */
    fun get(ctx: Context): Pair<String, String>? = try {
        val p = sp(ctx)
        val id = p?.getString("id", null)
        val pw = p?.getString("pw", null)
        if (id.isNullOrEmpty() || pw.isNullOrEmpty()) null else id to pw
    } catch (e: Exception) { null }

    /** Saving fresh details (a successful sign-in) also lifts any earlier "rejected" mark. */
    fun save(ctx: Context, id: String, password: String) {
        if (id.isBlank() || password.isEmpty()) return
        sp(ctx)?.edit()?.putString("id", id)?.putString("pw", password)?.putBoolean("rejected", false)?.apply()
    }

    /** A saved login that Amizone refused: keep it visible in Settings but never auto-submit it again. */
    fun markRejected(ctx: Context) { sp(ctx)?.edit()?.putBoolean("rejected", true)?.apply() }

    fun clear(ctx: Context) { sp(ctx)?.edit()?.clear()?.apply() }
}

/** What the login page looks like right now, reduced to yes/no facts (never the values themselves, except fields). */
class LoginPageState(
    val onLoginPage: Boolean,
    val fieldsFound: Boolean,
    val idEmpty: Boolean,
    val passwordEmpty: Boolean,
    /** Turnstile has issued a token and the page has stored it. Only its presence is read, never its value. */
    val tokenReady: Boolean,
    /** The page's own bot check: it has seen a real touch or mouse event. We never fake this. */
    val userActive: Boolean,
    /** What the user has typed so far, kept in memory only so it can be saved after a SUCCESSFUL sign-in. */
    val typedId: String,
    val typedPassword: String,
)

enum class AutoStep { NONE, WAIT, SUBMIT }

/**
 * Decides when the login form may be submitted for the user. The rules, all of which must hold:
 *  - saved details exist and haven't been rejected by Amizone, and we haven't tried very recently;
 *  - we have not already submitted on this login screen (exactly once, ever, per screen);
 *  - we are on the login page with the fields present;
 *  - Turnstile has issued its token AND the page's own bot check has seen a real touch. Those are the page's own
 *    conditions for "ready"; we only observe them and never create or work around either.
 */
class AutoLogin(private val hasDetails: Boolean, private val rejected: Boolean, private val recentlyTried: Boolean) {
    var submitted = false
        private set

    val allowed get() = hasDetails && !rejected && !recentlyTried && !submitted

    fun decide(p: LoginPageState): AutoStep = when {
        !allowed -> AutoStep.NONE
        !p.onLoginPage || !p.fieldsFound -> AutoStep.WAIT
        p.tokenReady && p.userActive -> AutoStep.SUBMIT
        else -> AutoStep.WAIT
    }

    fun markSubmitted() { submitted = true }

    /** Called when the login page shows up again after we submitted: Amizone said no. Never retried. */
    fun failedAfterSubmit() = submitted
}

/** The scripts run inside the login page. Pure strings, so their exact text can be tested. */
object LoginScripts {
    /** Reads the page's state as one JSON object. Values of the token are never read, only whether one exists. */
    val READ_STATE = """
        (function () {
          var u = document.getElementsByName('_UserName')[0];
          var p = document.getElementsByName('_Password')[0];
          var t = document.getElementById('RecaptchaToken');
          var c = document.querySelector('input[name="cf-turnstile-response"]');
          return JSON.stringify({
            fields: !!(u && p),
            id: u ? u.value : '',
            pw: p ? p.value : '',
            token: !!(t && t.value && c && c.value),
            active: (typeof userActive !== 'undefined') && userActive === true
          });
        })()
    """.trimIndent()

    /**
     * Fills the ID and password in, only if both are still empty (never over the user's own typing). Uses the same
     * events a password manager would so the page's own validation notices the values. JSON quoting makes any
     * character in the details safe (quotes, backslashes, angle brackets, line separators).
     */
    fun prefill(id: String, password: String): String {
        val jsId = JSONObject.quote(id)
        val jsPw = JSONObject.quote(password)
        return """
            (function () {
              var u = document.getElementsByName('_UserName')[0];
              var p = document.getElementsByName('_Password')[0];
              if (!u || !p || u.value || p.value) return false;
              function fill(el, v) {
                el.value = v;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              }
              fill(u, $jsId);
              fill(p, $jsPw);
              return true;
            })()
        """.trimIndent()
    }

    /**
     * Presses the page's own Login button. That runs the page's own validation (which checks for the token and for a
     * real touch), exactly as if the user had tapped it. This is deliberately NOT form.submit(), which would skip it.
     */
    val CLICK_LOGIN = """
        (function () {
          var b = document.querySelector('#loginform button[type="submit"], form[name="loginform"] button[type="submit"]');
          if (!b) return false;
          b.click();
          return true;
        })()
    """.trimIndent()

    fun parseState(json: String?, onLoginPage: Boolean): LoginPageState? {
        // evaluateJavascript hands back the JSON string itself as a quoted, escaped JSON string.
        val text = json ?: return null
        val obj = try {
            val unwrapped = if (text.startsWith("\"")) JSONArray("[$text]").getString(0) else text
            JSONObject(unwrapped)
        } catch (e: Exception) { return null }
        val id = obj.optString("id")
        val pw = obj.optString("pw")
        return LoginPageState(
            onLoginPage = onLoginPage, fieldsFound = obj.optBoolean("fields"),
            idEmpty = id.isEmpty(), passwordEmpty = pw.isEmpty(),
            tokenReady = obj.optBoolean("token"), userActive = obj.optBoolean("active"),
            typedId = id, typedPassword = pw,
        )
    }
}
