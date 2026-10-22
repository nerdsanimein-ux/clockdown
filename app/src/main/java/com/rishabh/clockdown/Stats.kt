package com.rishabh.clockdown

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** The version counter: a Google Apps Script web app writing to a Google Sheet (tools/stats). Empty = not set up, nothing is sent. */
const val STATS_URL = "https://script.google.com/macros/s/AKfycbzIpRU1veDZUogkXCSgMCrgoZCeRL4ga4WN0j28RHpk3vfbPxnsy5GzoMcKUs14Ixwe3w/exec"

/**
 * The version counter: at most once a day, one tiny anonymous message saying "an install of Clockdown is on version N" (and
 * whether it is still the version it was first installed with). The id is a random number this app made up; it is not the
 * phone's id and has nothing to do with the person or their Amizone account. Nothing else is sent. Settings can switch it off.
 */
object Stats {
    private const val GAP_MS = 20 * 3_600_000L // "once a day", allowing for the daily job running a little early
    private val client = OkHttpClient()

    fun enabled(ctx: Context) = ctx.prefs.getBoolean("statsOn", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.prefs.edit().putBoolean("statsOn", on).apply()

    /** The body sent. Pure, so what leaves the phone is pinned down by a test. */
    internal fun body(id: String, code: Int, name: String, fresh: Boolean, debug: Boolean): String =
        JSONObject().put("id", id).put("v", code).put("vn", name).put("fresh", fresh).put("debug", debug).toString()

    /** Blocking; off the main thread. [force] skips the once-a-day rule (debug test aid only). */
    fun ping(ctx: Context, force: Boolean = false): Boolean {
        if (!enabled(ctx) || STATS_URL.isEmpty()) return false
        val p = ctx.prefs
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("statsAt", 0) < GAP_MS) return false
        val id = p.getString("statsId", null) ?: UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("statsId", it).apply() }
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val fresh = pi.firstInstallTime == pi.lastUpdateTime // never updated since it was installed
        val json = body(id, UpdateChecker.installedCode(ctx), UpdateChecker.installedName(ctx), fresh, BuildConfig.DEBUG)
        return try {
            // Google answers a web-app POST with a redirect to its reply; OkHttp follows it, so success is a plain 200.
            client.newCall(Request.Builder().url(STATS_URL).post(json.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                r.isSuccessful.also { if (it) p.edit().putLong("statsAt", now).apply() }
            }
        } catch (e: IOException) { false } // offline: try again next time
    }
}
