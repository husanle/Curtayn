package com.filmritual.camera

import org.junit.Assert.*
import org.junit.Test

class ExposureMeteringTest {
    private fun index(mode: MeteringMode, grid: MeteringGrid, point: Pair<Float, Float>? = null) =
        meteringExposureIndex(readMetering(mode, grid, point).ev, 1f / 3, -12, 12)!!

    @Test fun spotExposureRespondsInOppositeDirectionsToDarkAndBrightPoints() {
        val frame = MeteringGrid(32, FloatArray(1024) { if (it % 32 < 16) .025f else .8f }, 0)
        assertEquals(.025f, meteredLuminance(MeteringMode.SPOT, frame, .1f to .5f), .001f)
        assertEquals(.8f, meteredLuminance(MeteringMode.SPOT, frame, .9f to .5f), .001f)
        assertTrue(index(MeteringMode.SPOT, frame, .1f to .5f) > 0)
        assertTrue(index(MeteringMode.SPOT, frame, .9f to .5f) < 0)
    }

    @Test fun fourModesHaveDifferentResponsesToABacklitSubjectAndBrightHighlights() {
        val frame = MeteringGrid(32, FloatArray(1024) {
            when {
                it % 32 in 8..23 && it / 32 in 8..23 -> .03f
                it % 32 >= 30 -> 1f
                else -> .2f
            }
        }, 0)
        val average = index(MeteringMode.AVERAGE, frame)
        val center = index(MeteringMode.CENTER, frame)
        val spot = index(MeteringMode.SPOT, frame, .5f to .5f)
        val highlights = index(MeteringMode.WEIGHTED, frame)
        assertEquals(0, average)
        assertTrue(center > average)
        assertTrue(spot > center)
        assertTrue(highlights < average)
        assertEquals(4, setOf(average, center, spot, highlights).size)
    }

    @Test fun exposureGainDoesNotAccumulateIntoMoreCompensation() {
        for (mode in MeteringMode.entries) {
            val readings = listOf(.25f, .5f, 1f, 2f, 4f).map { gain ->
                val frame = MeteringGrid(32, FloatArray(1024) { (if (it % 32 < 16) .02f else .15f) * gain }, 0)
                index(mode, frame, .1f to .5f)
            }
            assertEquals("$mode must not chase its own exposure changes", 1, readings.distinct().size)
        }
    }

    @Test fun spotCanRequestTheFullTargetInOnePass() {
        val frame = MeteringGrid(32, FloatArray(1024) { if (it % 32 < 8) .0125f else .4f }, 0)
        assertEquals(12, index(MeteringMode.SPOT, frame, .1f to .5f))
        assertTrue(index(MeteringMode.SPOT, frame, .9f to .5f) < 0)
    }

    @Test fun uniformLightingDoesNotInventDifferencesBetweenModes() {
        for (brightness in listOf(.01f, .18f, .8f)) {
            val frame = MeteringGrid(32, FloatArray(1024) { brightness }, 0)
            for (mode in MeteringMode.entries) assertEquals(0, index(mode, frame, .2f to .8f))
        }
    }

    @Test fun highlightModeNeverBrightensTheCameraBaseline() {
        val frame = MeteringGrid(32, FloatArray(1024) { if (it % 32 >= 30) 1f else .025f }, 0)
        assertTrue(index(MeteringMode.WEIGHTED, frame) < 0)
        val uniform = MeteringGrid(32, FloatArray(1024) { .1f }, 0)
        assertEquals(0, index(MeteringMode.WEIGHTED, uniform))
    }

    @Test fun sensorLimitsAndInvalidInputsCannotRunAway() {
        assertEquals(6, meteringExposureIndex(8f, 1f / 3, -6, 6))
        assertEquals(-6, meteringExposureIndex(-8f, 1f / 3, -6, 6))
        assertNull(meteringExposureIndex(Float.NaN, 1f / 3, -6, 6))
        assertNull(meteringExposureIndex(.5f, 0f, -6, 6))
        assertNull(meteringExposureIndex(.5f, Float.NaN, -6, 6))
        assertNull(meteringExposureIndex(.5f, 1f / 3, 6, -6))
        assertEquals(0f, linearChannel(0), .0001f)
        assertEquals(1f, linearChannel(255), .0001f)
        assertEquals(.21586f, linearChannel(128), .0001f)
    }

    @Test fun spotStaysInsideImageAtAllCorners() {
        val frame = MeteringGrid(32, FloatArray(1024) { it / 1024f }, 0)
        assertTrue(meteredLuminance(MeteringMode.SPOT, frame, 0f to 0f) < .1f)
        assertTrue(meteredLuminance(MeteringMode.SPOT, frame, 1f to 1f) > .9f)
    }

    @Test fun invalidPixelsAndBlackFramesStillGiveFiniteDecisions() {
        for (values in listOf(FloatArray(1024), FloatArray(1024) { Float.NaN })) {
            for (mode in MeteringMode.entries) {
                assertTrue(readMetering(mode, MeteringGrid(32, values, 0), .5f to .5f).ev.isFinite())
            }
        }
    }
}
