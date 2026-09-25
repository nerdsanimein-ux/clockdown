package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStyleTest {
    private val cls = Event(id = 1, name = "c", startMillis = 0, source = AMIZONE, courseCode = "X")
    private val own = Event(id = 2, name = "m", startMillis = 0)

    @Test
    fun aWidgetsOwnStyleBeatsEverything() {
        val e = own.copy(widgetStyle = WidgetStyle.BOLD.id)
        assertEquals(WidgetStyle.MONO, resolveStyle(WidgetStyle.MONO, e, WidgetStyle.GLASS))
    }

    @Test
    fun aTimersOwnStyleBeatsTheClassDefault() {
        assertEquals(WidgetStyle.BOLD, resolveStyle(null, own.copy(widgetStyle = "bold"), WidgetStyle.GLASS))
        assertEquals(WidgetStyle.PAPER, resolveStyle(null, cls.copy(widgetStyle = "paper"), WidgetStyle.GLASS))
    }

    @Test
    fun classesFollowTheClassDefaultAndTimersDoNot() {
        assertEquals(WidgetStyle.GLASS, resolveStyle(null, cls, WidgetStyle.GLASS))
        assertEquals(WidgetStyle.CARD, resolveStyle(null, own, WidgetStyle.GLASS))
        assertEquals(WidgetStyle.CARD, resolveStyle(null, null, WidgetStyle.GLASS))
    }

    @Test
    fun anUnknownStyleIdFallsBack() {
        assertNull(WidgetStyle.of("nope"))
        assertEquals(WidgetStyle.CARD, resolveStyle(null, own.copy(widgetStyle = "from-a-newer-version"), WidgetStyle.GLASS))
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
}
