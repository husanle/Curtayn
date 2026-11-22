package com.filmritual.camera

import org.junit.Assert.*
import org.junit.Test

class LensZoomTest {
    @Test fun compatibleModeIncludesNativeLensesAndOnlySupportedZooms() {
        val choices = compatibleFocals(24.0, .6f, 4.3f, setOf(14, 24, 75, 100, 200))
        assertTrue(choices.containsAll(setOf(14, 24, 48, 72, 75, 100, 103)))
        assertFalse(choices.contains(120))
        assertFalse(choices.contains(200))
    }

    @Test fun fixedZoomDoesNotInventAnUnavailableLens() {
        assertEquals(setOf(23), compatibleFocals(23.2, 1f, 1f, setOf(14, 23, 75)))
        assertTrue(compatibleFocals(Double.NaN, .6f, 10f, setOf(24)).isEmpty())
        assertTrue(compatibleFocals(24.0, 0f, 10f, setOf(24)).isEmpty())
    }
}
