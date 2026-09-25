package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCheckTest {
    private fun v(code: Int, type: String?, body: String) = SessionCheck.classify(code, type, body)

    @Test
    fun aListOfClassesMeansTheSessionWorks() {
        assertEquals(Verdict.OK, v(200, "application/json; charset=utf-8", """[{"id":1}]"""))
        assertEquals(Verdict.OK, v(200, "application/json", "[]")) // an empty day is a good answer, not an expiry
        assertEquals(Verdict.OK, v(200, null, "﻿\n [ ]")) // stray whitespace or a byte-order mark
    }

    @Test
    fun redirectsAndRefusalsMeanSignedOut() {
        for (code in listOf(301, 302, 303, 307, 308)) assertEquals("HTTP $code", Verdict.EXPIRED, v(code, null, ""))
        assertEquals(Verdict.EXPIRED, v(401, null, ""))
        assertEquals(Verdict.EXPIRED, v(403, "text/html", "<html>Forbidden</html>"))
    }

    @Test
    fun theLoginPageServedInsteadOfDataMeansSignedOut() {
        assertEquals(Verdict.EXPIRED, v(200, "text/html; charset=utf-8", "<!DOCTYPE html><title>Amizone</title>"))
        assertEquals(Verdict.EXPIRED, v(200, null, "<form id=\"loginform\" action=\"/\">"))
        assertEquals(Verdict.EXPIRED, v(200, null, "  <html>")) // leading whitespace doesn't hide it
        assertEquals(Verdict.EXPIRED, v(200, "application/json", "{\"error\":\"not signed in\"}")) // not a list
        assertEquals(Verdict.EXPIRED, v(200, "application/json", ""))
    }

    @Test
    fun serverAndEndpointProblemsAreNotBlamedOnTheSession() {
        assertEquals(Verdict.PROBLEM, v(500, "text/html", "<html>error</html>"))
        assertEquals(Verdict.PROBLEM, v(503, null, ""))
        assertEquals(Verdict.PROBLEM, v(404, "text/html", "<html>not found</html>")) // endpoint moved: don't say "sign in"
        assertEquals(Verdict.PROBLEM, v(429, null, ""))
    }

    private val hour = 3_600_000L

    @Test
    fun oneFailedRefreshRightAfterAConfirmedOneDoesNotWarn() {
        assertEquals(false, shouldWarnUnconfirmed(true, expired = false, flagged = true, lastSync = 10 * hour, now = 10 * hour + 7 * 60_000))
    }

    @Test
    fun aTimetableUnconfirmedForHoursDoesWarn() {
        assertEquals(true, shouldWarnUnconfirmed(true, expired = false, flagged = true, lastSync = 10 * hour, now = 14 * hour))
    }

    @Test
    fun anExpiredSignInAlwaysWarnsAndNothingWarnsWhenNotFlaggedOrNotConnected() {
        assertEquals(true, shouldWarnUnconfirmed(true, expired = true, flagged = true, lastSync = 10 * hour, now = 10 * hour + 1))
        assertEquals(false, shouldWarnUnconfirmed(true, expired = false, flagged = false, lastSync = 1, now = 99 * hour))
        assertEquals(false, shouldWarnUnconfirmed(false, expired = true, flagged = true, lastSync = 1, now = 99 * hour))
    }
}
