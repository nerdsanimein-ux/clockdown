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
