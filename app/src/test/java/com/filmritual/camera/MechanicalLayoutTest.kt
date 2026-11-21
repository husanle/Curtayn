package com.filmritual.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicalLayoutTest {
    @Test fun linkedShuttersHaveAMatchingLeverAndFinder() {
        for (lever in Advance.entries) assertEquals(lever, lever.defaultShutter.matchingAdvance)
        for (shutter in Shutter.entries) assertEquals(shutter, shutter.matchingFinder.defaultShutter)
        assertEquals(Advance.NIKON_SP, Shutter.NIKON.matchingAdvance)
        assertEquals(Advance.CANON_AE1, Shutter.AE1.matchingAdvance)
    }

    @Test fun allControlCombinationsKeepTheDialAndShutterApart() {
        for (advance in Advance.entries) for (shutter in Shutter.entries) {
            val layout = MechanicalLayout(advance, shutter)
            val label = "$advance / $shutter"
            val dialRight = layout.dialX + layout.dialDiameter / 2
            val buttonRadius = layout.shutterDiameter / 2
            assertTrue(label, layout.shutterX - buttonRadius - dialRight >= .025f)
            assertTrue(label, layout.shutterY > buttonRadius && layout.shutterY + buttonRadius < 1 / layout.aspect)
            assertFalse(label, layout.canStartWinding(dialRight - .005f, layout.dialY))
            assertTrue(label, layout.canStartWinding(layout.pivotX, layout.pivotY + .09f))
            if (!layout.coaxial) {
                assertFalse(label, layout.canStartWinding(layout.shutterX, layout.shutterY + .01f))
            } else {
                assertEquals(layout.pivotX, layout.shutterX, 0f)
                assertEquals(layout.pivotY, layout.shutterY, 0f)
            }
        }
    }
}
