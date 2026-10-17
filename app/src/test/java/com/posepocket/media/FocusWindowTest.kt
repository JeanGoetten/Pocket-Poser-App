package com.posepocket.media

import org.junit.Assert.*
import org.junit.Test

class FocusWindowTest {
    @Test fun windowUsesNormalizedCoordinatesAndClampsToSource() {
        assertArrayEquals(intArrayOf(200,60,600,420),
            FocusWindow(.25f,.1f,1f,.8f).pixelBounds(800,600))
        assertArrayEquals(intArrayOf(0,0,400,300),
            FocusWindow(0f,0f,1f,1f).pixelBounds(400,300))
    }

    @Test fun invalidAndTinyRegionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FocusWindow(.1f,.1f,.2f,.9f) }
        assertThrows(IllegalArgumentException::class.java) { FocusWindow(.1f,.1f,1.1f,.9f) }
        assertThrows(IllegalArgumentException::class.java) { FocusWindow(Float.NaN,0f,1f,1f) }
    }
}
