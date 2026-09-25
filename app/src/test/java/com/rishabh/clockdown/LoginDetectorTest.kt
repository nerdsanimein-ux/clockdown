package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDetectorTest {
    private val cookiesIn = "ASP.NET_SessionId=abc; .ASPXAUTH=def; __RequestVerificationToken=ghi"
    private val cookiesOut = "ASP.NET_SessionId=abc; __RequestVerificationToken=ghi"

    @Test
    fun signedInNeedsTheLandingPageAndTheAuthCookie() {
        assertTrue(LoginDetector.signedIn("https://s.amizone.net/Home", cookiesIn))
        assertTrue(LoginDetector.signedIn("https://s.amizone.net/home/", cookiesIn)) // case and trailing slash don't matter
    }

    @Test
    fun theLoginPageItselfIsNeverASuccess() {
        // Wrong password or captcha not solved yet: the browser stays on the login page.
        assertFalse(LoginDetector.signedIn("https://s.amizone.net/", cookiesIn))
        assertFalse(LoginDetector.signedIn("https://s.amizone.net", cookiesIn))
        assertFalse(LoginDetector.signedIn("https://s.amizone.net/Login/ForgotPassword", cookiesIn))
    }

    @Test
    fun theLandingPageWithoutTheAuthCookieIsNotEnough() {
        assertFalse(LoginDetector.signedIn("https://s.amizone.net/Home", cookiesOut))
        assertFalse(LoginDetector.signedIn("https://s.amizone.net/Home", null))
        assertFalse(LoginDetector.signedIn("https://s.amizone.net/Home", ".ASPXAUTHX=1")) // a look-alike name doesn't count
    }

    @Test
    fun otherSitesAndInsecureAddressesNeverCount() {
        assertFalse(LoginDetector.signedIn("https://evil.example/Home", cookiesIn))
        assertFalse(LoginDetector.signedIn("https://s.amizone.net.evil.example/Home", cookiesIn))
        assertFalse(LoginDetector.signedIn("http://s.amizone.net/Home", cookiesIn))
        assertFalse(LoginDetector.signedIn(null, cookiesIn))
        assertFalse(LoginDetector.signedIn("not a url", cookiesIn))
    }

    @Test
    fun theLoginWindowOnlyShowsThePortalAndItsCaptcha() {
        assertTrue(LoginDetector.allowedPage("https://s.amizone.net/"))
        assertTrue(LoginDetector.allowedPage("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertFalse(LoginDetector.allowedPage("http://s.amizone.net/"))
        assertFalse(LoginDetector.allowedPage("https://s.amizone.net.evil.example/"))
        assertFalse(LoginDetector.allowedPage("https://example.com/"))
        assertFalse(LoginDetector.allowedPage("javascript:alert(1)"))
    }
}

class EmojiTest {
    private fun cls(name: String, code: String = "X1") = Event(id = 1, name = name, startMillis = 0, source = AMIZONE, courseCode = code)

    @Test
    fun suggestsAnEmojiForCommonSubjectsAnywhere() {
        assertEquals("💻", cls("INTRODUCTION TO PROGRAMMING").emojiOrDefault())
        assertEquals("🧪", cls("ORGANIC CHEMISTRY").emojiOrDefault()) // subject words win over broader ones like "organi(sation)"
        assertEquals("📐", cls("LINEAR ALGEBRA").emojiOrDefault())
        assertEquals("⚖️", cls("BUSINESS LAW").emojiOrDefault())
    }

    @Test
    fun aWordInsideAnotherWordDoesNotFire() {
        // Matching is on word beginnings: WEB does not fire inside COBWEB.
        assertEquals(false, cls("COBWEB STUDIES").emojiOrDefault() == "💻")
    }

    @Test
    fun anythingUnrecognisedGetsAStableStudyEmojiAndOwnEventsAPin() {
        val a = cls("SOMETHING UNHEARD OF", "QQ9")
        assertEquals(a.emojiOrDefault(), a.copy(id = 5, startMillis = 99).emojiOrDefault()) // same course, same emoji
        assertEquals("📌", Event(name = "Dentist", startMillis = 0).emojiOrDefault())
        assertEquals("🎯", Event(name = "x", startMillis = 0, emoji = "🎯").emojiOrDefault()) // the user's choice always wins
    }
}
