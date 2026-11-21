package com.filmritual.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.roundToInt

internal data class FilmStripLayout(val photoWidth: Int, val photoHeight: Int) {
    init { require(photoWidth > 0 && photoHeight > 0) }

    val portrait = photoHeight > photoWidth
    val along = maxOf(photoWidth, photoHeight)
    val across = minOf(photoWidth, photoHeight)
    val rail = (across * .15f).roundToInt().coerceAtLeast(1)
    val end = (along * .019f).roundToInt().coerceAtLeast(1)
    val stripLength = along + end * 2
    val stripWidth = across + rail * 2
    val width get() = if (portrait) stripWidth else stripLength
    val height get() = if (portrait) stripLength else stripWidth
    val photoLeft get() = if (portrait) rail else end
    val photoTop get() = if (portrait) end else rail
    val pitch = along / 8f

    fun holeCenter(index: Int) = end + (index + .5f) * pitch
}

data class FilmEdge(val film: Film, val frame: Int) {
    init { require(film.hasPerforations && frame > 0) }

    val stockLine: String get() = (film.brand + " " + film.label).uppercase(Locale.ROOT)
    val codeLine: String get() {
        val process = when {
            film.key.startsWith("kodachrome-") -> "K-14"
            film.key == "kodacolor-x" -> "C-22"
            film.type == "彩色反转片" -> "E-6"
            film.type == "彩色负片" -> "C-41"
            else -> "B&W"
        }
        return listOfNotNull("135", film.productCode, process).joinToString("  /  ")
    }
    val frameLine: String get() = frame.toString().padStart(2, '0')
}

internal fun Canvas.drawFilmStrip(layout: FilmStripLayout, edge: FilmEdge) {
    val outer = when {
        edge.film.monochrome -> Color.rgb(29, 29, 26)
        edge.film.brand == "Fujifilm" -> Color.rgb(26, 32, 28)
        else -> Color.rgb(33, 26, 21)
    }
    val ink = if (edge.film.monochrome) Color.rgb(182, 180, 162) else Color.rgb(205, 155, 99)
    val r = layout.rail.toFloat()
    val height = layout.stripWidth.toFloat()
    val length = layout.stripLength.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val savePoint = save()
    if (layout.portrait) {
        translate(layout.width.toFloat(), 0f)
        rotate(90f)
    }
    drawColor(outer)

    paint.color = Color.rgb(73, 63, 48)
    drawRect(layout.end.toFloat(), r - maxOf(1f, r * .019f),
        length - layout.end, height - r + maxOf(1f, r * .019f), paint)

    val holeHalfWidth = layout.pitch * .23f
    val holeHalfHeight = r * .225f
    val corner = minOf(holeHalfWidth, holeHalfHeight) * .22f
    for (centerY in listOf(r * .377f, height - r * .377f)) {
        paint.shader = LinearGradient(0f, centerY - holeHalfHeight, 0f, centerY + holeHalfHeight,
            Color.rgb(245, 242, 229), Color.rgb(216, 209, 190), Shader.TileMode.CLAMP)
        for (index in 0 until 8) {
            val centerX = layout.holeCenter(index)
            drawRoundRect(centerX - holeHalfWidth, centerY - holeHalfHeight,
                centerX + holeHalfWidth, centerY + holeHalfHeight, corner, corner, paint)
        }
    }
    paint.shader = null
    paint.color = ink
    paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    paint.textSize = r * .215f
    val left = layout.holeCenter(0) - holeHalfWidth
    val right = layout.holeCenter(7) + holeHalfWidth
    val upperBaseline = r * .88f
    val lowerBaseline = height - r * .735f
    val stockWidth = paint.measureText(edge.stockLine)
    val allowed = (right - left) * .77f
    if (stockWidth > allowed) paint.textSize *= allowed / stockWidth
    drawText(edge.stockLine, left, upperBaseline, paint)
    paint.textSize = r * .215f
    drawText(edge.codeLine, left, lowerBaseline, paint)
    paint.textAlign = Paint.Align.RIGHT
    drawText(edge.frameLine, right, upperBaseline, paint)
    drawText(edge.frameLine, right, lowerBaseline, paint)
    restoreToCount(savePoint)
}
