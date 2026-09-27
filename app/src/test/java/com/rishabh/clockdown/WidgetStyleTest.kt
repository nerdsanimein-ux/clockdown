package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStyleTest {
    private val cls = Event(id = 1, name = "c", startMillis = 0, source = AMIZONE, courseCode = "X")
    private val own = Event(id = 2, name = "m", startMillis = 0)

    @Test
    fun aTimersOwnStyleBeatsTheClassDefault() {
        assertEquals(WidgetStyle.BOLD, resolveStyle(own.copy(widgetStyle = "bold"), WidgetStyle.GLASS))
        assertEquals(WidgetStyle.PAPER, resolveStyle(cls.copy(widgetStyle = "paper"), WidgetStyle.GLASS))
    }

    @Test
    fun classesFollowTheClassDefaultAndTimersDoNot() {
        assertEquals(WidgetStyle.GLASS, resolveStyle(cls, WidgetStyle.GLASS))
        assertEquals(WidgetStyle.CARD, resolveStyle(own, WidgetStyle.GLASS))
        assertEquals(WidgetStyle.CARD, resolveStyle(null, WidgetStyle.GLASS))
    }

    @Test
    fun anUnknownStyleIdFallsBack() {
        assertNull(WidgetStyle.of("nope"))
        assertEquals(WidgetStyle.CARD, resolveStyle(own.copy(widgetStyle = "from-a-newer-version"), WidgetStyle.GLASS))
    }

    @Test
    fun retiredStyleIdsMigrateToTheClosestKeptStyle() {
        // v1.1 dropped these three; anything already saved with one of these ids must keep resolving, not go blank.
        assertEquals(WidgetStyle.CARD, WidgetStyle.of("gradient"))
        assertEquals(WidgetStyle.GLASS, WidgetStyle.of("mono"))
        assertEquals(WidgetStyle.GLASS, WidgetStyle.of("outline"))
        assertEquals(WidgetStyle.CARD, resolveStyle(own.copy(widgetStyle = "gradient"), WidgetStyle.GLASS))
        assertEquals(WidgetStyle.GLASS, resolveStyle(own.copy(widgetStyle = "outline"), WidgetStyle.GLASS))
    }

    @Test
    fun twoWidgetsShowingTheSameTimerLookTheSame() {
        // A Timer widget has no style of its own any more (see v1.2): only the event's own choice can affect it, so
        // there is exactly one answer for "what does this timer look like", no matter how many widgets show it.
        val e = own.copy(widgetStyle = "paper")
        assertEquals(resolveStyle(e, WidgetStyle.GLASS), resolveStyle(e, WidgetStyle.GLASS))
        assertEquals(WidgetStyle.PAPER, resolveStyle(e, WidgetStyle.GLASS))
    }

    @Test
    fun everyStyleHasAUniqueIdAndEveryLayoutExists() {
        assertEquals(WidgetStyle.entries.size, WidgetStyle.entries.map { it.id }.toSet().size)
        for (kind in Kind.entries) {
            val layouts = Variant.entries.map { Looks.layout(kind, it) }
            assertEquals("each variant of $kind has its own layout", Variant.entries.size, layouts.toSet().size)
            assertTrue(layouts.all { it != 0 })
        }
    }

    @Test
    fun progressStyleNoneIsKeptAndAutomaticNeverPicksIt() {
        assertEquals(3, own.copy(progressStyle = 3).styleIndex())
        assertEquals(0, own.copy(progressStyle = 0).styleIndex())
        assertEquals(true, own.copy(id = 5).styleIndex() in 0..2) // automatic: ring, dots or bars, never none
        assertEquals(true, cls.styleIndex() in 0..2)
    }

    @Test
    fun crashReportsBlankSecretsAndIds() {
        assertEquals("boom cookie=[removed]", CrashReporting.scrub("boom cookie=ASP.NET_SessionId=abc; .ASPXAUTH=def"))
        assertEquals("id # failed", CrashReporting.scrub("id 12345678 failed"))
        assertEquals("token [removed]", CrashReporting.scrub("token " + "A".repeat(40)))
        assertEquals(true, CrashReporting.scrub("word ".repeat(100))!!.length <= 300)
        assertEquals("Cannot access database on the main thread", CrashReporting.scrub("Cannot access database on the main thread"))
    }
}
