package com.rishabh.clockdown

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassPhotosTest {
    @Test
    fun folderNamesAreSafeForTheGallery() {
        assertEquals("Maths   Stats", ClassPhotos.folderName(Event(name = "Maths / Stats", startMillis = 0)))
        assertEquals("Class", ClassPhotos.folderName(Event(name = " ? ", startMillis = 0)))
    }
}
