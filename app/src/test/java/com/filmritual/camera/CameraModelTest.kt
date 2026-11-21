package com.filmritual.camera

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CameraModelTest {
    @Test fun filmBordersAddRailsAlongThePhotoWithoutCropping() {
        val landscape = FilmStripLayout(4032, 3024)
        val portrait = FilmStripLayout(3024, 4032)
        assertEquals(landscape.width, portrait.height)
        assertEquals(landscape.height, portrait.width)
        assertEquals(landscape.photoLeft, portrait.photoTop)
        assertEquals(landscape.photoTop, portrait.photoLeft)
        assertEquals(4032, landscape.width - 2 * landscape.photoLeft)
        assertEquals(3024, landscape.height - 2 * landscape.photoTop)
        assertEquals(504f, landscape.holeCenter(1) - landscape.holeCenter(0), .001f)
        assertEquals(landscape.stripLength.toFloat(), landscape.holeCenter(7) + landscape.holeCenter(0), .001f)
    }

    @Test fun filmEdgeUsesTheLoadedStockCodeAndFrame() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first(File::isDirectory)
        val films = File(root, "films/catalog.tsv").reader().use(Film::readCatalog).associateBy(Film::key)
        val provia = FilmEdge(films.getValue("fuji-provia-100f"), 4)
        assertEquals("FUJIFILM PROVIA 100F", provia.stockLine)
        assertEquals("135  /  RDP III  /  E-6", provia.codeLine)
        assertEquals("04", provia.frameLine)
        assertEquals("135  /  400TX  /  B&W", FilmEdge(films.getValue("kodak-trix-400-shell"), 12).codeLine)
        assertEquals("135  /  400PR  /  B&W", FilmEdge(films.getValue("fuji-neopan-400"), 1).codeLine)
        assertEquals("135  /  CX  /  C-22", FilmEdge(films.getValue("kodacolor-x"), 1).codeLine)
        assertEquals("135  /  KR  /  K-14", FilmEdge(Film.KODACHROME, 1).codeLine)
        assertEquals("135  /  B&W", FilmEdge(films.getValue("fomapan-400-action"), 1).codeLine)
        assertFalse(films.getValue("fuji-fp-100c-box").hasPerforations)
        assertFalse(films.getValue("fuji-fp-3000b-box").hasPerforations)
    }

    @Test fun everyMechanicalRecordingContainsPlayablePcm() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first(File::isDirectory)
        val files = File(root, "sounds").listFiles { file -> file.extension == "wav" }.orEmpty()
        assertEquals(5, files.size)
        for (file in files) {
            val pcm = wavePcm(file.readBytes())
            assertTrue(file.name, pcm.size in 3200..19200)
            val samples = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertTrue(file.name, (0 until samples.remaining()).any { kotlin.math.abs(samples[it].toInt()) >= 9000 })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedRecordingIsRejected() {
        wavePcm(ByteArray(44))
    }

    @Test fun needsWindBeforeEverySuccessfulExposure() {
        val fresh = Roll(Film.EKTAR)
        assertEquals(fresh, fresh.press())
        val ready = fresh.wind()
        assertEquals(Phase.ARMED, ready.phase)
        assertEquals(ready, ready.wind())
        val pending = ready.press()
        assertEquals(Phase.CAPTURING, pending.phase)
        assertEquals(pending, pending.press().wind())
        val saved = pending.complete(true)
        assertEquals(1, saved.frames)
        assertEquals(Phase.NEEDS_WIND, saved.phase)
        assertEquals(saved, saved.complete(true).press())
        assertEquals(2, saved.wind().press().complete(true).frames)
    }

    @Test fun failedSaveKeepsArmedWithoutCounting() {
        val armed = Roll(Film.DELTA100, 14).wind()
        assertEquals(armed, armed.press().complete(false))
        assertEquals(Film.DELTA100, armed.film)
    }

    @Test fun lensesUseTheDetectedFocalLengthsWithoutInventingASlot() {
        val wide = CameraPath("0", "2", 14.6, 2.2f)
        val main = CameraPath("0", "0", 23.2, 6f)
        val tele = CameraPath("4", null, 115.4, 16f)
        val shortTele = CameraPath("0", "4", 70.0, 12f)
        val longTele = CameraPath("0", "5", 75.1, 13f)
        val unknown = CameraPath("5", null, Double.NaN, Float.NaN)
        val matches = lensPaths(listOf(wide, main, tele, shortTele, longTele, unknown))
        assertEquals(listOf(wide), matches[15])
        assertEquals(listOf(main), matches[23])
        assertEquals(listOf(tele), matches[115])
        assertEquals(listOf(shortTele), matches[70])
        assertEquals(listOf(longTele), matches[75])
        assertNull(matches[73])
    }

    @Test fun logicalMainCameraPrecedesItsPhysicalAlias() {
        val logical = CameraPath("0", null, 23.2, 6f)
        val physical = CameraPath("0", "b", 22.2, 6f)
        assertEquals(mapOf(23 to listOf(logical, physical)), lensPaths(listOf(physical, logical)))
    }

    @Test fun fullSensorDimensionsSurviveRotationAndFilmEdges() {
        val portrait = PhotoOrientation(8192, 6144, 90)
        val landscape = PhotoOrientation(8192, 6144, 180)
        val reverse = PhotoOrientation(8192, 6144, 270)
        assertEquals(6144, portrait.width)
        assertEquals(8192, portrait.height)
        assertEquals(6144, portrait.shiftX)
        assertEquals(0, portrait.shiftY)
        assertEquals(8192, landscape.shiftX)
        assertEquals(6144, landscape.shiftY)
        assertEquals(0, reverse.shiftX)
        assertEquals(8192, reverse.shiftY)
        val strip = FilmStripLayout(portrait.width, portrait.height)
        assertEquals(portrait.width, strip.width - strip.photoLeft * 2)
        assertEquals(portrait.height, strip.height - strip.photoTop * 2)
    }

    @Test fun cubeSamplesRedFastestAndInterpolates() {
        val cube = Cube.parse(("# test\nTITLE \"passthrough\"\nLUT_3D_SIZE 2\n" +
            "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1").reader())
        assertEquals(listOf(255, 0, 128), cube.sample(1f, 0f, .5f))
        assertEquals(listOf(64, 128, 191), cube.sample(.25f, .5f, .75f))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidCubeIsRejected() {
        Cube.parse("LUT_3D_SIZE 2\n0 0 0\n".reader())
    }

    @Test fun allFilmAssetsLoadAndBlackAndWhiteProfilesDesaturate() {
        val roots = listOf(File("src/main/assets"), File("app/src/main/assets"))
        val root = roots.first(File::isDirectory)
        val films = File(root, "films/catalog.tsv").reader().use(Film::readCatalog)
        assertEquals(48, films.size)
        assertEquals(24, films.count { it.upstream != null })
        assertEquals(setOf(100, 200, 400), films.filter { it.brand == "Fomapan" }.map { it.iso }.toSet())
        for (film in films) {
            val lookup = film.look?.let(Cube::fromLook) ?: File(root, "luts/" + film.key + ".cube").reader().use(Cube::parse)
            film.image?.let { assertTrue(File(root, it).isFile) }
            film.reference?.let { assertTrue(File(root, it).isFile) }
            assertTrue(film.credit.isNotBlank())
            val saturated = lookup.sample(.9f, .32f, .1f)
            if (film.monochrome && film.type != "黑白即显片") assertTrue(film.label, saturated.max() - saturated.min() <= 3)
        }
        assertTrue(File(root, "materials/film-atlas.png").isFile)
    }

    @Test fun isoDetentsAndGrainIncreaseWithSpeed() {
        assertEquals(400, adjacentIso(200, 1))
        assertEquals(200, adjacentIso(400, -1))
        assertEquals(100, adjacentIso(80, 1))
        assertNull(adjacentIso(3200, 1))
        assertNull(adjacentIso(25, -1))
        assertTrue(Grain.at(400, false).strength > Grain.at(100, false).strength)
        assertTrue(Grain.at(1600, true).size > Grain.at(400, true).size)
        assertTrue(Grain.at(400, true).strength > Grain.at(400, false).strength)
    }
}
