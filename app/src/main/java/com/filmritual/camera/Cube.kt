package com.filmritual.camera

import java.io.Reader
import kotlin.math.floor
import kotlin.math.roundToInt

class Cube(val size: Int, val rgb: ByteArray) {
    fun sample(red: Float, green: Float, blue: Float): List<Int> {
        val axes = listOf(red, green, blue).map { it.coerceIn(0f, 1f) * (size - 1) }
        val low = axes.map { floor(it).toInt() }
        val high = low.map { (it + 1).coerceAtMost(size - 1) }
        return (0..2).map { channel ->
            var result = 0f
            for (corner in 0..7) {
                val index = (0..2).map { axis -> if (corner and (1 shl axis) == 0) low[axis] else high[axis] }
                val weight = (0..2).fold(1f) { product, axis ->
                    val fraction = axes[axis] - low[axis]
                    product * if (corner and (1 shl axis) == 0) 1 - fraction else fraction
                }
                val colorOffset = 3 * (index[0] + size * (index[1] + size * index[2])) + channel
                result += (rgb[colorOffset].toInt() and 255) * weight
            }
            result.roundToInt()
        }
    }

    companion object {
        fun fromLook(look: FilmLook): Cube {
            val edge = 17
            val rgb = ByteArray(edge * edge * edge * 3)
            var position = 0
            val greenWeight = .69f + look.green
            val redWeight = .23f - look.green
            for (b in 0 until edge) for (g in 0 until edge) for (r in 0 until edge) {
                val red = r.toFloat() / (edge - 1)
                val green = g.toFloat() / (edge - 1)
                val blue = b.toFloat() / (edge - 1)
                val luma = red * redWeight + green * greenWeight + blue * .08f
                val toe = luma * (1 - luma)
                val tone = (luma + (luma - .5f) * (look.contrast - 1) * (4 * toe + .15f))
                    .coerceIn(0f, 1f) * (1 - look.fade * 1.3f) + look.fade
                val midtone = toe * 4
                fun channel(input: Float, shift: Float) =
                    ((tone + (input - luma) * look.saturation + shift * midtone).coerceIn(0f, 1f) * 255).roundToInt().toByte()
                rgb[position++] = channel(red, look.warmth)
                rgb[position++] = channel(green, if (look.saturation == 0f) 0f else look.green)
                rgb[position++] = channel(blue, -look.warmth)
            }
            return Cube(edge, rgb)
        }

        fun parse(reader: Reader): Cube = reader.buffered().useLines { lines ->
            var edge = 0
            var index = 0
            var buffer = ByteArray(0)
            lines.forEach { line ->
                val cleaned = line.substringBefore('#').trim().trimStart('\uFEFF')
                if (cleaned.isEmpty()) return@forEach
                val fields = cleaned.split(Regex("\\s+"))
                when (fields[0]) {
                    "TITLE" -> Unit
                    "LUT_3D_SIZE" -> {
                        require(edge == 0 && fields.size == 2)
                        edge = fields[1].toInt().also { require(it in 2..65) }
                        buffer = ByteArray(edge * edge * edge * 3)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val bound = if (fields[0] == "DOMAIN_MIN") 0f else 1f
                        require(fields.size == 4 && fields.drop(1).all { it.toFloat() == bound })
                    }
                    else -> {
                        require(edge > 0 && fields.size == 3 && index <= buffer.size - 3)
                        fields.forEach { field ->
                            val value = field.toFloat().also { require(it.isFinite()) }
                            buffer[index++] = (value.coerceIn(0f, 1f) * 255).roundToInt().toByte()
                        }
                    }
                }
            }
            require(edge > 0 && index == buffer.size) { "Invalid .cube row count" }
            Cube(edge, buffer)
        }
    }
}
