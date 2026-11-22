package com.filmritual.camera

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ImprintLanguageTest {
    private val midnight = Instant.parse("2026-11-21T16:05:00Z")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun dateBackStylesUseCaptureTimeAndLocalCalendar() {
        assertEquals("'26 11 22", DateImprint(ImprintStyle.NIKON, false, midnight, shanghai).text)
        assertEquals("26  11  22", DateImprint(ImprintStyle.CANON, false, midnight, shanghai).text)
        assertEquals("2026.11.22  00:05", DateImprint(ImprintStyle.FUJI, true, midnight, shanghai).text)
        assertEquals("2026.11.21", DateImprint(ImprintStyle.FUJI, false, midnight, ZoneId.of("UTC")).text)
        assertEquals("", DateImprint(ImprintStyle.OFF, true, midnight, shanghai).text)
    }

    @Test fun manualLanguageAlwaysOverridesIpAndSystem() {
        assertEquals(UiLanguage.EN, resolveLanguage(LanguageChoice.ENGLISH, "CN", "zh"))
        assertEquals(UiLanguage.ZH, resolveLanguage(LanguageChoice.CHINESE, "US", "en"))
        assertEquals(UiLanguage.ZH, resolveLanguage(LanguageChoice.AUTO, "cn", "en"))
        assertEquals(UiLanguage.EN, resolveLanguage(LanguageChoice.AUTO, "US", "zh"))
        assertEquals(UiLanguage.ZH, resolveLanguage(LanguageChoice.AUTO, null, "zh-Hans"))
        assertEquals(UiLanguage.EN, resolveLanguage(LanguageChoice.AUTO, null, "fr"))
    }

    @Test fun mixedFilmMetadataAndDynamicStatusTranslateTogether() {
        assertEquals("Color negative · 135 cartridge · Custom approximation", translate("彩色负片 · 135 卷壳 · 自制近似", UiLanguage.EN))
        assertEquals("Capture size  8192 × 6144", translate("镜头拍摄尺寸  8192 × 6144", UiLanguage.EN))
        assertEquals("Spot metering", translate("点测光", UiLanguage.EN))
        assertEquals("点测光", translate("点测光", UiLanguage.ZH))
    }

    @Test fun dateBackFollowsTheFilmWhenPortraitIsRotated() {
        val horizontal = FilmImprintTransform(4000f, 3000f, 0)
        val portrait = FilmImprintTransform(3000f, 4000f, 90)
        val reverse = FilmImprintTransform(3000f, 4000f, 270)
        assertEquals(4000f, horizontal.filmWidth, .001f)
        assertEquals(4000f, portrait.filmWidth, .001f)
        assertEquals(3000f, portrait.filmHeight, .001f)
        // The same bottom-right film corner lands at bottom-left after clockwise rotation.
        assertEquals(0f, portrait.shiftX - portrait.filmHeight, .001f)
        assertEquals(4000f, portrait.shiftY + portrait.filmWidth, .001f)
        assertEquals(0f, reverse.shiftY - reverse.filmWidth, .001f)
        assertEquals(3000f, reverse.shiftX + reverse.filmHeight, .001f)
    }

    @Test fun physicalOrientationStillControlsPhotosWithPortraitUiLocked() {
        assertEquals(0, captureRotation(0))
        assertEquals(3, captureRotation(90))
        assertEquals(2, captureRotation(180))
        assertEquals(1, captureRotation(270))
        assertEquals(0, captureRotation(359))
        assertNull(captureRotation(-1))
    }
}
