package com.filmritual.camera

enum class MeteringMode(val title: String, val shortTitle: String, val detail: String) {
    AVERAGE("平均测光", "平均", "让整个取景画面参与测光"),
    CENTER("中央权重测光", "中央权重", "优先参考中央，兼顾周围"),
    SPOT("点测光", "点测", "轻点取景窗，测量所点的小区域"),
    WEIGHTED("高光权重测光", "高光权重", "优先测量亮部，减少高光溢出")
}

enum class MeteringStatus { WAITING, CUSTOM, COMPENSATED, SYSTEM }

internal data class MeteringZone(val left: Float, val top: Float, val right: Float, val bottom: Float, val weight: Int)

internal fun MeteringMode.zones(point: Pair<Float, Float>?, limit: Int): List<MeteringZone> {
    if (limit <= 0) return emptyList()
    return when (this) {
        MeteringMode.AVERAGE -> listOf(MeteringZone(0f, 0f, 1f, 1f, 1000))
        MeteringMode.CENTER -> {
            val center = MeteringZone(.20f, .20f, .80f, .80f, 880)
            if (limit == 1) listOf(center)
            else listOf(center, MeteringZone(0f, 0f, 1f, 1f, 120))
        }
        MeteringMode.SPOT -> {
            val (x, y) = point ?: (.5f to .5f)
            val size = .10f
            val left = (x - size / 2).coerceIn(0f, 1f - size)
            val top = (y - size / 2).coerceIn(0f, 1f - size)
            listOf(MeteringZone(left, top, left + size, top + size, 1000))
        }
        // Replaced by preview-derived highlight regions when a frame is available.
        MeteringMode.WEIGHTED -> listOf(MeteringZone(0f, 0f, 1f, 1f, 1000))
    }
}

internal fun highlightZones(luminance: List<Float>, columns: Int, rows: Int, limit: Int): List<MeteringZone> {
    require(columns > 0 && rows > 0 && luminance.size == columns * rows)
    if (limit <= 0) return emptyList()
    val brightest = luminance.maxOrNull() ?: 0f
    if (brightest < 8f || brightest - (luminance.minOrNull() ?: 0f) < 12f) {
        return MeteringMode.AVERAGE.zones(null, limit)
    }
    return luminance.indices.sortedByDescending { luminance[it] }
        .filter { luminance[it] >= brightest * .88f }.take(minOf(limit, 3)).map { index ->
            val x = index % columns
            val y = index / columns
            MeteringZone(x.toFloat() / columns, y.toFloat() / rows, (x + 1f) / columns, (y + 1f) / rows,
                (luminance[index] / brightest * 1000).toInt().coerceIn(1, 1000))
        }
}
