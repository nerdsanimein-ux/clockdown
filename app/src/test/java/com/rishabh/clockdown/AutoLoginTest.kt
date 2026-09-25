package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoginTest {
    private fun page(
        login: Boolean = true, fields: Boolean = true, token: Boolean = true, active: Boolean = true,
    ) = LoginPageState(login, fields, idEmpty = false, passwordEmpty = false, tokenReady = token, userActive = active, typedId = "", typedPassword = "")

    private fun auto(saved: Boolean = true, rejected: Boolean = false, recent: Boolean = false) = AutoLogin(saved, rejected, recent)

    @Test
    fun submitsOnlyWhenTheTokenIsReadyAndThePageHasSeenARealTouch() {
        val a = auto()
        assertEquals(AutoStep.WAIT, a.decide(page(token = false, active = false)))
        assertEquals(AutoStep.WAIT, a.decide(page(token = true, active = false))) // no touch yet: the page's own check says no
        assertEquals(AutoStep.WAIT, a.decide(page(token = false, active = true)))  // captcha not done yet
        assertEquals(AutoStep.SUBMIT, a.decide(page(token = true, active = true)))
    }

    @Test
    fun waitsUntilTheFormIsActuallyThere() {
        assertEquals(AutoStep.WAIT, auto().decide(page(fields = false)))
        assertEquals(AutoStep.WAIT, auto().decide(page(login = false)))
    }

    @Test
    fun neverSubmitsTwiceOnOneLoginScreen() {
        val a = auto()
        assertEquals(AutoStep.SUBMIT, a.decide(page()))
        a.markSubmitted()
        repeat(50) { assertEquals(AutoStep.NONE, a.decide(page())) } // however many polls follow
        assertFalse(a.allowed)
    }

    @Test
    fun doesNothingWithoutUsableSavedDetails() {
        assertEquals(AutoStep.NONE, auto(saved = false).decide(page()))
        assertEquals(AutoStep.NONE, auto(rejected = true).decide(page())) // Amizone refused them before: never again
        assertEquals(AutoStep.NONE, auto(recent = true).decide(page()))   // a recent attempt (e.g. screen rotated): back off
    }

    @Test
    fun aFailureAfterSubmittingIsOnlyReportedIfWeSubmitted() {
        val a = auto()
        assertFalse(a.failedAfterSubmit()) // the login page simply loading is not a failure
        a.markSubmitted()
        assertTrue(a.failedAfterSubmit())
    }
}

class LoginScriptsTest {
    @Test
    fun prefillQuotesAnyCharacterSafely() {
        val nasty = "pa\"ss'w\\ord</script><b> line\nbreak"
        val js = LoginScripts.prefill("A12345", nasty)
        // The raw quote, backslash, angle brackets and line separators must not appear unescaped inside the script.
        assertFalse(js.contains("pa\"ss"))
        assertFalse(js.contains("</script>"))
        assertFalse(js.contains(" "))
        assertTrue(js.contains("\\\"")) // the quote is escaped
        assertTrue(js.contains("A12345"))
        assertTrue(js.contains("u.value || p.value")) // never overwrites what the user typed
    }

    @Test
    fun loginIsPressedThroughThePagesOwnButtonNotByBypassingIt() {
        assertTrue(LoginScripts.CLICK_LOGIN.contains(".click()"))
        assertFalse(LoginScripts.CLICK_LOGIN.contains(".submit("))  // form.submit() would skip the page's validation
        assertFalse(LoginScripts.CLICK_LOGIN.contains("dispatchEvent")) // and no synthetic events
        assertFalse(LoginScripts.READ_STATE.contains("dispatchEvent"))
    }

    @Test
    fun readingTheStateNeverReadsTheTokenValue() {
        // Only "is there a token" is reported.
        assertTrue(LoginScripts.READ_STATE.contains("token: !!("))
        assertFalse(LoginScripts.READ_STATE.contains("token: t.value"))
    }

    @Test
    fun parsesTheStateEvenThoughEvaluateJavascriptDoublyEncodesIt() {
        val raw = "\"{\\\"fields\\\":true,\\\"id\\\":\\\"A1\\\",\\\"pw\\\":\\\"x\\\",\\\"token\\\":true,\\\"active\\\":false}\""
        val s = LoginScripts.parseState(raw, onLoginPage = true)!!
        assertTrue(s.fieldsFound); assertTrue(s.tokenReady); assertFalse(s.userActive)
        assertEquals("A1", s.typedId); assertEquals("x", s.typedPassword)
        assertNull(LoginScripts.parseState("null", true))
        assertNull(LoginScripts.parseState(null, true))
        assertNotNull(LoginScripts.parseState("{\"fields\":false}", true))
    }
}
