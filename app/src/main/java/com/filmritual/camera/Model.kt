package com.filmritual.camera

import java.io.Reader
import kotlin.math.ln
import kotlin.math.roundToInt

data class Film(
    val key: String,
    val label: String,
    val brand: String,
    val type: String,
    val iso: Int,
    val format: String,
    val image: String?,
    val reference: String?,
    val credit: String,
    val source: String,
    val upstream: String?,
    val look: FilmLook? = null,
    val productCode: String? = null
) {
    val badge get() = if (upstream != null) "Film-Luts" else "自制近似"
    val monochrome get() = type.startsWith("黑白")
    val hasPerforations get() = format.startsWith("135")

    companion object {
        val EKTAR = Film("ektar-100", "Ektar 100", "Kodak", "彩色负片", 100, "135 卷壳", null,
            "films/ektar-100.jpg", "El Grafo · CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Kodak_Ektar_100_135_film_cartridge_%2801%29.jpg", "luts/negative_color/kodak_ektar_100.cube")
        val KODACHROME = Film("kodachrome-64", "Kodachrome 64", "Kodak", "彩色反转片", 64, "135 卷壳", null,
            "films/kodachrome-64.jpg", "Dnalor_01；Ubcule 修图 · CC BY-SA 3.0 AT", "https://commons.wikimedia.org/wiki/File:Kodachrome_64_-_Film_for_colour_slides.jpg", "luts/colorslide/kodak_kodachrome_64.cube",
            productCode = "KR")
        val DELTA100 = Film("delta-100", "Delta 100", "Ilford", "黑白负片", 100, "135 卷壳", null,
            "films/delta-100.jpg", "El Grafo · CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Ilford_Delta_100_Professional_135_film_cartridge_01.jpg", "luts/bw/ilford_delta_100.cube")
        val DELTA3200 = Film("delta-3200", "Delta 3200", "Ilford", "黑白负片", 3200, "135 卷壳", null,
            "films/delta-3200.jpg", "El Grafo · CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Ilford_Delta_3200_Professional_135_film_cartridge_01.jpg", "luts/bw/ilford_delta_3200.cube")
        val originals = listOf(EKTAR, KODACHROME, DELTA100, DELTA3200)

        fun readCatalog(reader: Reader): List<Film> = reader.buffered().useLines { lines ->
            val films = originals + lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 13) { "Invalid film record" }
                val upstream = fields[10].ifBlank { null }
                val look = fields[11].takeIf(String::isNotBlank)?.split(',')?.map(String::toFloat)?.let {
                    require(it.size == 5 && it.all(Float::isFinite))
                    FilmLook(it[0], it[1], it[2], it[3], it[4])
                }
                require((upstream == null) != (look == null))
                Film(fields[0], fields[1], fields[2], fields[3], fields[4].toInt(), fields[5], fields[6].ifBlank { null },
                    fields[7].ifBlank { null }, fields[8], fields[9], upstream, look, fields[12].ifBlank { null })
            }.toList()
            require(films.distinctBy(Film::key).size == films.size)
            films
        }
    }
}

data class FilmLook(val saturation: Float, val contrast: Float, val fade: Float, val warmth: Float, val green: Float)

val isoStops = listOf(25, 50, 64, 80, 100, 125, 160, 200, 400, 800, 1600, 3200)

fun adjacentIso(current: Int, direction: Int): Int? =
    if (direction > 0) isoStops.firstOrNull { it > current } else isoStops.lastOrNull { it < current }

data class Grain(val strength: Float, val size: Float) {
    companion object {
        fun at(iso: Int, mono: Boolean): Grain {
            val stops = (ln(iso.coerceIn(25, 3200) / 100.0) / ln(2.0)).toFloat()
            return Grain(
                (.060f + .012f * stops) * if (mono) 1.10f else 1f,
                .94f + .071f * stops
            )
        }
    }
}

data class FilmRender(val film: Film, val iso: Int) {
    val grain = Grain.at(iso, film.monochrome)
}

enum class Finder(val title: String, val style: String, val defaultShutter: Shutter) {
    LEICA("Leica M6", "亮框旁轴", Shutter.LEICA),
    ZEISS("Zeiss Ikon ZM", "亮框旁轴 · 左侧测光", Shutter.ZEISS),
    NIKON_SP("Nikon SP", "旁轴主取景窗", Shutter.NIKON),
    NIKON("Nikon FM2", "裂像单反", Shutter.FM2),
    CANON("Canon P", "三组亮框旁轴", Shutter.CANON),
    AE1("Canon AE-1", "裂像单反 · 侧边标尺", Shutter.AE1),
    AGFA("Agfa Optima Sensor", "明亮取景窗", Shutter.AGFA),
    PENTAX("Pentax K1000", "指针测光单反", Shutter.PENTAX)
}

enum class Shutter(val title: String, val recording: String?, val clicks: List<Click>) {
    LEICA("Leica M6", "leica-m3", listOf(Click(0, 910, .57), Click(22, 410, .38))),
    ZEISS("Zeiss Ikon ZM", null, listOf(Click(0, 1420, .68), Click(17, 730, .20), Click(38, 510, .48))),
    NIKON("Nikon SP", null, listOf(Click(0, 650, .68), Click(14, 410, .38), Click(33, 310, .44))),
    CANON("Canon P", null, listOf(Click(0, 1240, .57), Click(25, 560, .58))),
    FM2("Nikon FM2", "nikon-fm2", listOf(Click(0, 760, .7), Click(28, 410, .60))),
    AE1("Canon AE-1", "canon-ae1", listOf(Click(0, 950, .62), Click(35, 490, .57))),
    PENTAX("Pentax K1000", "pentax-k1000", listOf(Click(0, 790, .62), Click(39, 390, .58))),
    AGFA("Agfa Optima Sensor", null, listOf(Click(0, 1550, .30), Click(12, 990, .16)));
}

enum class Advance(val title: String, val sweep: Float, val teeth: Int, val pitch: Float, val defaultShutter: Shutter) {
    LEICA("徕卡 · M6 TTL", 41f, 5, .89f, Shutter.LEICA),
    ZEISS("蔡司 · Ikon ZM", 36f, 5, 1.13f, Shutter.ZEISS),
    NIKON("尼康 · FM2", 38f, 6, 1.06f, Shutter.FM2),
    NIKON_SP("尼康 · SP", 40f, 5, .98f, Shutter.NIKON),
    CANON("佳能 · Canon P", 36f, 5, 1.19f, Shutter.CANON),
    CANON_AE1("佳能 · AE-1", 42f, 5, 1.10f, Shutter.AE1),
    PENTAX("宾得 · K1000", 40f, 4, .96f, Shutter.PENTAX),
    AGFA("爱克发 · Optima 1035", 40f, 4, .94f, Shutter.AGFA);
}

internal val Shutter.matchingAdvance get() = Advance.entries.first { it.defaultShutter == this }

internal val Shutter.matchingFinder get() = Finder.entries.first { it.defaultShutter == this }

internal val Advance.coaxialShutter get() = this == Advance.LEICA || this == Advance.AGFA

enum class DialStyle(val title: String, val pitch: Float) {
    BLACK("黑漆滚花", .84f),
    SILVER("银色金属", 1.16f),
    RESIN("黑色树脂", 1f);
}

data class Click(val offsetMs: Int, val frequency: Int, val amplitude: Double)

enum class Phase { NEEDS_WIND, ARMED, CAPTURING }

data class Roll(val film: Film, val frames: Int = 0, val phase: Phase = Phase.NEEDS_WIND) {
    fun wind() = if (phase == Phase.NEEDS_WIND) copy(phase = Phase.ARMED) else this
    fun press() = if (phase == Phase.ARMED) copy(phase = Phase.CAPTURING) else this
    fun complete(saved: Boolean) = if (phase != Phase.CAPTURING) this else if (saved) {
        copy(frames = frames + 1, phase = Phase.NEEDS_WIND)
    } else copy(phase = Phase.ARMED)
}

data class CameraPath(val parent: String, val physical: String?, val equivalent: Double, val focal: Float)

fun lensPaths(paths: List<CameraPath>): Map<Int, List<CameraPath>> {
    val groups = mutableListOf<MutableList<CameraPath>>()
    for (path in paths.filter { it.equivalent.isFinite() && it.equivalent > 0 }.sortedBy { it.equivalent }) {
        val last = groups.lastOrNull()
        if (last != null && last.any { existing ->
            val sameReading = existing.equivalent.roundToInt() == path.equivalent.roundToInt()
            val sameParent = existing.parent == path.parent && (existing.physical == null) != (path.physical == null)
            val close = path.equivalent - existing.equivalent <= maxOf(1.0, existing.equivalent * .075)
            sameReading || (sameParent && close)
        }) {
            last.add(path)
        } else {
            groups.add(mutableListOf(path))
        }
    }
    return groups.associate { group ->
        val ordered = group.sortedBy { it.physical != null }
        ordered.first().equivalent.roundToInt() to ordered
    }
}
