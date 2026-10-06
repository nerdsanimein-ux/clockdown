package com.rishabh.clockdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

/** Debug builds only. Lets the test suite reach states that are otherwise hard to create. Reports facts, never secrets. */
class DebugHooks : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            // Simulates the user having changed their Amizone password: the saved one is now wrong.
            "com.rishabh.clockdown.DEBUG_CORRUPT_PASSWORD" ->
                CredentialStore.get(ctx)?.let { (id, _) -> CredentialStore.save(ctx, id, "wrong-" + UUID.randomUUID()) }
            // Simulates a dead session: the cookies are replaced with ones the portal will refuse.
            "com.rishabh.clockdown.DEBUG_EXPIRE_SESSION" ->
                Session.save(ctx, "ASP.NET_SessionId=invalid; .ASPXAUTH=invalid", Session.userAgent(ctx) ?: "Clockdown-test")
            // Runs one real sync now and reports the outcome and, if it failed, why (a label, never content).
            "com.rishabh.clockdown.DEBUG_SYNC" -> {
                val pending = goAsync()
                kotlin.concurrent.thread {
                    val result = AmizoneSync.run(ctx, force = true)
                    pending.resultData = "sync=$result failure=${ctx.lastFailure} unconfirmed=${ctx.unconfirmedSince != null}"
                    pending.finish()
                }
                return
            }
            // Session-lifetime measurement: "init" copies the current login into two variants (A = as saved, B = follows
            // Set-Cookie renewals); every other call probes both and appends status + cookie NAMES and expiry to probe.log.
            "com.rishabh.clockdown.DEBUG_PROBE" -> {
                val pending = goAsync()
                kotlin.concurrent.thread { pending.resultData = SessionProbe.run(ctx, intent.getBooleanExtra("init", false)); pending.finish() }
                return
            }
            // Runs one attendance refresh now and reports the outcome and COUNTS only (never a course, a name or a number from the data).
            "com.rishabh.clockdown.DEBUG_ATTENDANCE" -> {
                val pending = goAsync()
                kotlin.concurrent.thread {
                    val r = AttendanceSync.run(ctx, force = true)
                    val snap = AttendanceStore.load(ctx)
                    pending.resultData = "att=$r failure=${ctx.attFailure} courses=${snap?.courses?.size} tracked=${snap?.courses?.count { it.tracked }} records=${snap?.courses?.sumOf { it.records.size }}"
                    pending.finish()
                }
                return
            }
            // Test aid for the notifications: rewinds the saved attendance so the NEXT real refresh sees a change.
            // "marks" forgets today's rows; "zone" pretends the first non-green subject used to be fully green.
            "com.rishabh.clockdown.DEBUG_ATT_REWIND" -> {
                val snap = AttendanceStore.load(ctx)
                if (snap != null) {
                    val today = java.time.LocalDate.now(AMIZONE_ZONE)
                    val marks = intent.getStringExtra("what") != "zone"
                    var done = false
                    val rewound = snap.courses.map { c ->
                        if (marks) c.copy(records = c.records.filter { it.date != today }, attended = c.attended - c.records.filter { it.date == today }.sumOf { it.present }, total = c.total - c.records.filter { it.date == today }.sumOf { it.present + it.absent })
                        else if (!done && c.tracked && ctx.zoneOf(c) != Zone.GREEN) { done = true; c.copy(attended = c.total, records = emptyList()) } else c
                    }
                    AttendanceStore.save(ctx, snap.copy(courses = rewound))
                }
            }
            // Fires an alarm as if its time had come: kind=class (the first unmarked class today, in an at-risk subject if any) or kind=timer.
            "com.rishabh.clockdown.DEBUG_ALARM" -> {
                val pending = goAsync()
                kotlin.concurrent.thread {
                    val dao = AppDb.get(ctx)
                    val e = if (intent.getStringExtra("kind") == "timer") {
                        val id = dao.byId(900001) ?: Event(id = 900001, name = "Test timer", startMillis = System.currentTimeMillis() + 3_600_000).also { dao.upsert(it) }
                        id
                    } else {
                        val snap = AttendanceStore.load(ctx)
                        val dayStart = java.time.LocalDate.now(AMIZONE_ZONE).atStartOfDay(AMIZONE_ZONE).toInstant().toEpochMilli()
                        val today = dao.classesBetween(dayStart, dayStart + 86_400_000L)
                        today.firstOrNull { snap != null && snap.markFor(it) == ClassMark.NOT_MARKED && snap.course(it.courseCode)?.let { c -> ctx.zoneOf(c) != Zone.GREEN } == true } ?: today.first()
                    }
                    Alarms.fire(ctx, e.id, e.title(), e.room.orEmpty(), "Starting soon")
                    pending.resultData = "fired ${e.source}"; pending.finish()
                }
                return
            }
            // Fakes "a newer release exists" (the real GitHub answer is ignored while this is on, see UpdateChecker.check).
            // Extras: clear=true removes the fake; rewind=true pretends 25 hours passed since the last dialog; reset=true forgets
            // the dialog and notification history. Nothing is downloaded: "Update now" will report a failed download.
            "com.rishabh.clockdown.DEBUG_FAKE_UPDATE" -> {
                val p = ctx.prefs
                if (intent.getBooleanExtra("clear", false)) p.edit().remove("debugFakeUpdate").remove("updRelease").remove("updEtag").apply()
                else {
                    val code = UpdateChecker.installedCode(ctx) + 1
                    val json = org.json.JSONObject().put("tag_name", "$code").put("name", "Clockdown 9.$code")
                        .put("body", "## What's new\n- Update prompts when a new version is out\n- Keeps your Amizone sign-in alive through the day\n- A quieter notification, once per version\n- Fourth line\n- Fifth line\n- Sixth line that is cut off\n")
                        .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "Clockdown.apk").put("size", 1000).put("browser_download_url", "https://github.invalid/Clockdown.apk"))).toString()
                    p.edit().putBoolean("debugFakeUpdate", true).putString("updRelease", json).apply()
                }
                if (intent.getBooleanExtra("rewind", false)) p.edit().putLong("updPromptAt", System.currentTimeMillis() - 25 * 3_600_000L).apply()
                if (intent.getBooleanExtra("reset", false)) p.edit().remove("updPromptAt").remove("updNotifiedCode").apply()
                setResultData("updPromptAt=${p.getLong("updPromptAt", 0)} notified=${p.getInt("updNotifiedCode", 0)} available=${UpdateChecker.available(ctx)?.versionName}")
                return
            }
            // Runs the daily background update job's body now (the same function the real worker calls).
            "com.rishabh.clockdown.DEBUG_UPDATE_RUN" -> {
                val pending = goAsync()
                kotlin.concurrent.thread { UpdateChecker.dailyRun(ctx); pending.resultData = "ran notified=${ctx.prefs.getInt("updNotifiedCode", 0)}"; pending.finish() }
                return
            }
            // Runs one keep-alive now, regardless of the time of day, and reports whether the saved cookie changed (never its value).
            "com.rishabh.clockdown.DEBUG_KEEPALIVE" -> {
                val pending = goAsync()
                kotlin.concurrent.thread {
                    val before = Session.cookies(ctx)
                    AmizoneSync.keepAlive(ctx)
                    pending.resultData = "keepalive ran; cookieChanged=${Session.cookies(ctx) != before} expired=${ctx.sessionExpired} session=${Session.active(ctx)}"
                    pending.finish()
                }
                return
            }
            // Sends one harmless test event to the crash-report service, to prove the wiring works.
            "com.rishabh.clockdown.DEBUG_SENTRY_TEST" -> {
                CrashReporting.note("Clockdown test event (safe to ignore)")
                io.sentry.Sentry.captureException(IllegalStateException("Clockdown test crash (safe to ignore)"))
                io.sentry.Sentry.flush(5000)
            }
            "com.rishabh.clockdown.DEBUG_RESET_BATTERY_PROMPT" ->
                ctx.prefs.edit().remove("batteryAsked").remove("batteryWasExempt").remove("batteryWarnDismissed").apply()
        }
        setResultData(
            "credentials=${CredentialStore.status(ctx)} session=${Session.active(ctx)} " +
                "batteryExempt=${Battery.exempt(ctx)} batteryAsked=${ctx.prefs.getBoolean("batteryAsked", false)} " +
                "autoLoginAt=${ctx.prefs.getLong("autoLoginAt", 0)}",
        )
    }
}

/** Debug only. Measures how Amizone sessions end. Logs HTTP status, which cookies the server (re)sets and their expiry; never a cookie value. */
object SessionProbe {
    private val LF = System.lineSeparator()
    private fun jar(ctx: Context) = java.io.File(ctx.filesDir, "probe_cookies.txt")
    private fun log(ctx: Context) = java.io.File(ctx.filesDir, "probe.log")
    private fun parse(s: String) = s.split(";").map { it.trim() }.filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }.toMutableMap()

    fun run(ctx: Context, init: Boolean): String {
        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).withNano(0)
        if (init) {
            val c = Session.cookies(ctx) ?: return "no session"
            jar(ctx).writeText(c + LF + c)
            log(ctx).writeText("$now init names=${parse(c).keys}" + LF)
            return "init ok"
        }
        val lines = jar(ctx).readLines().toMutableList()
        val client = okhttp3.OkHttpClient.Builder().followRedirects(false).build()
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
        val out = StringBuilder()
        for ((i, name) in listOf("A", "B").withIndex()) {
            val line = try {
                val r = client.newCall(okhttp3.Request.Builder()
                    .url("https://s.amizone.net/Calendar/home/GetDiaryEvents?start=$day&end=${day.plusDays(1)}")
                    .header("Cookie", lines[i]).header("User-Agent", Session.userAgent(ctx) ?: "")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01").header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://s.amizone.net/Home").build()).execute()
                val body = r.body.string()
                val verdict = SessionCheck.classify(r.code, r.header("Content-Type"), body)
                val sets = r.headers("Set-Cookie")
                if (name == "B") { val m = parse(lines[i]); sets.forEach { sc -> val kv = sc.substringBefore(";"); m[kv.substringBefore("=")] = kv.substringAfter("=") }; lines[i] = m.entries.joinToString("; ") { "${it.key}=${it.value}" } }
                "$name HTTP ${r.code} $verdict setCookies=" + sets.joinToString(" | ") { sc -> sc.substringBefore("=") + " [" + sc.split(";").drop(1).map { it.trim() }.filter { a -> a.lowercase().startsWith("expires") || a.lowercase().startsWith("max-age") }.joinToString(",") + "]" }
            } catch (e: Exception) { "$name ERR ${e.javaClass.simpleName}" }
            out.append(line).append("; ")
        }
        jar(ctx).writeText(lines.joinToString(LF))
        log(ctx).appendText("$now $out" + LF)
        return out.toString()
    }
}
