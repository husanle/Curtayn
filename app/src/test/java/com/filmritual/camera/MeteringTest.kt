package com.filmritual.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringTest {
    @Test fun spotFollowsTouchAndKeepsItsSizeAtEdges() {
        val centered = MeteringMode.SPOT.zones(null, 1).single()
        val edge = MeteringMode.SPOT.zones(1f to 0f, 1).single()
        assertEquals(.45f, centered.left, .001f)
        assertEquals(.9f, edge.left, .001f)
        assertEquals(0f, edge.top, .001f)
        assertEquals(1f, edge.right, .001f)
        assertEquals(.1f, edge.bottom, .001f)
    }

    @Test fun allModesRespectTheDeviceRegionLimit() {
        for (mode in MeteringMode.entries) for (limit in 0..6) {
            val zones = mode.zones(null, limit)
            assertTrue(zones.size <= limit)
            zones.forEach {
                assertTrue(it.weight in 1..1000)
                assertTrue(it.left in 0f..it.right && it.right <= 1f)
                assertTrue(it.top in 0f..it.bottom && it.bottom <= 1f)
            }
        }
        assertEquals(1f, MeteringMode.AVERAGE.zones(null, 1).single().right)
        assertTrue(MeteringMode.CENTER.zones(null, 1).single().right < 1f)
        assertEquals(1, MeteringMode.WEIGHTED.zones(null, 1).size)
        assertNotEquals(MeteringMode.CENTER.zones(null, 2), MeteringMode.WEIGHTED.zones(null, 2))
    }

    @Test fun highlightMeteringFindsBrightPatchEvenWithOnlyOneRegion() {
        val scene = MutableList(64) { 25f }.also { it[6] = 230f; it[55] = 210f }
        val single = highlightZones(scene, 8, 8, 1).single()
        assertEquals(.75f, single.left, .001f)
        assertEquals(0f, single.top, .001f)
        assertEquals(1000, single.weight)
        val multiple = highlightZones(scene, 8, 8, 4)
        assertEquals(2, multiple.size)
        assertTrue(multiple[1].weight < multiple[0].weight)
        assertTrue(highlightZones(scene, 8, 8, 0).isEmpty())
    }

    @Test fun evenOrDarkScenesUseTheWholeFrame() {
        assertEquals(MeteringMode.AVERAGE.zones(null, 1), highlightZones(List(64) { 100f }, 8, 8, 1))
        assertEquals(MeteringMode.AVERAGE.zones(null, 1), highlightZones(List(64) { it / 10f }, 8, 8, 1))
    }
}
