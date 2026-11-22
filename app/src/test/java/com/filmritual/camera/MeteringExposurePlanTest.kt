package com.filmritual.camera

import org.junit.Assert.*
import org.junit.Test

class MeteringExposurePlanTest {
    @Test fun aNewSpotTapSupersedesAnUnfinishedExposureImmediately() {
        val plan = MeteringExposurePlan()
        plan.select(MeteringMode.SPOT)
        assertEquals(9, plan.offer(9, 1f / 3, 0, busy = false))
        val previousRevision = plan.revision
        // The camera has not completed the first request when the user taps a bright area.
        plan.select(MeteringMode.SPOT)
        assertEquals(-6, plan.offer(-6, 1f / 3, 30, busy = true))
        assertTrue(plan.revision > previousRevision)
        assertEquals(-6, plan.desiredIndex)
    }

    @Test fun spotDoesNotMakeMoreRequestsAsItsOwnBrightnessChanges() {
        val plan = MeteringExposurePlan()
        plan.select(MeteringMode.SPOT)
        assertEquals(6, plan.offer(6, 1f / 3, 0, busy = false))
        for (sample in 1..100) assertNull(plan.offer(sample % 9 - 4, 1f / 3, sample * 250L, busy = false))
        assertEquals(6, plan.desiredIndex)
        plan.select(MeteringMode.SPOT)
        assertEquals(-3, plan.offer(-3, 1f / 3, 25100, busy = false))
    }

    @Test fun switchingModeDoesNotWaitForTheOldModeToConverge() {
        val plan = MeteringExposurePlan()
        for ((mode, index) in listOf(MeteringMode.WEIGHTED to -6, MeteringMode.CENTER to 3, MeteringMode.AVERAGE to 0)) {
            plan.select(mode)
            assertEquals(index, plan.offer(index, 1f / 3, 0, busy = true))
        }
    }

    @Test fun continuousMeteringRequiresAStableSignificantSceneChange() {
        val plan = MeteringExposurePlan()
        plan.select(MeteringMode.CENTER)
        assertEquals(0, plan.offer(0, 1f / 3, 0, busy = false))
        assertNull(plan.offer(3, 1f / 3, 450, busy = false))
        assertNull(plan.offer(1, 1f / 3, 900, busy = false))
        assertNull(plan.offer(3, 1f / 3, 1350, busy = true))
        assertNull(plan.offer(3, 1f / 3, 1800, busy = false))
        assertEquals(3, plan.offer(3, 1f / 3, 2250, busy = false))
        assertNull(plan.offer(0, 1f / 3, 2400, busy = false))
    }

    @Test fun smallPreviewNoiseDoesNotCauseRepeatedHardwareAdjustments() {
        val plan = MeteringExposurePlan()
        plan.select(MeteringMode.WEIGHTED)
        assertEquals(-3, plan.offer(-3, 1f / 3, 0, busy = false))
        repeat(30) { sample ->
            assertNull(plan.offer(if (sample % 2 == 0) -2 else -4, 1f / 3, 1000 + sample * 450L, busy = false))
        }
        assertEquals(-3, plan.desiredIndex)
    }
}
