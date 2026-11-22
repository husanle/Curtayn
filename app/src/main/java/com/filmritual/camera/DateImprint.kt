package com.filmritual.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ImprintStyle(val title: String) {
    OFF("关闭"), NIKON("尼康参考"), CANON("佳能参考"), FUJI("富士参考")
}

data class DateImprint(val style: ImprintStyle, val withTime: Boolean, val instant: Instant, val zone: ZoneId) {
    val text: String get() {
        val pattern = when (style) {
            ImprintStyle.NIKON -> "''yy MM dd"
            ImprintStyle.CANON -> "yy  MM  dd"
            ImprintStyle.FUJI -> "yyyy.MM.dd"
            ImprintStyle.OFF -> return ""
        } + if (withTime) "  HH:mm" else ""
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(zone).format(instant)
    }
}

internal data class FilmImprintTransform(val width: Float, val height: Float, val rotation: Int) {
    init { require(width > 0 && height > 0 && rotation in listOf(0, 90, 180, 270)) }
    val sideways get() = rotation == 90 || rotation == 270
    val filmWidth get() = if (sideways) height else width
    val filmHeight get() = if (sideways) width else height
    val shiftX get() = if (rotation == 90 || rotation == 180) width else 0f
    val shiftY get() = if (rotation == 180 || rotation == 270) height else 0f
}

internal fun Canvas.drawFilmDateImprint(stamp: DateImprint, photo: RectF, rotation: Int, displayScale: Float = 1f) {
    if (photo.width() <= 0 || photo.height() <= 0) return
    val layout = FilmImprintTransform(photo.width(), photo.height(), ((rotation % 360) + 360) % 360)
    val saved = save()
    try {
        translate(photo.left + layout.shiftX, photo.top + layout.shiftY)
        rotate(layout.rotation.toFloat())
        drawDateImprint(stamp, RectF(0f, 0f, layout.filmWidth, layout.filmHeight), displayScale)
    } finally { restoreToCount(saved) }
}

/** Seven-segment date-back lettering. The same renderer is used for preview and the JPEG. */
internal fun Canvas.drawDateImprint(stamp: DateImprint, bounds: RectF, displayScale: Float = 1f) {
    val text = stamp.text
    if (text.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) return
    val shortSide = minOf(bounds.width(), bounds.height())
    val margin = shortSide * .045f
    val cell = minOf(shortSide * .032f * displayScale, (bounds.width() - margin * 2) / (text.length * .77f))
    val step = cell * .77f
    val x = bounds.right - margin - text.length * step
    val y = bounds.bottom - margin - cell
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = cell * if (stamp.style == ImprintStyle.CANON) .105f else .08f
        strokeCap = if (stamp.style == ImprintStyle.FUJI) Paint.Cap.SQUARE else Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    val tint = when (stamp.style) {
        ImprintStyle.CANON -> Color.rgb(255, 163, 50)
        ImprintStyle.FUJI -> Color.rgb(255, 194, 70)
        else -> Color.rgb(255, 178, 55)
    }
    fun lettering() {
        for ((index, char) in text.withIndex()) {
            val left = x + index * step
            val w = cell * .51f
            val h = cell
            val segments = listOf(
                floatArrayOf(0f, 0f, w, 0f), floatArrayOf(w, 0f, w, h / 2),
                floatArrayOf(w, h / 2, w, h), floatArrayOf(0f, h, w, h),
                floatArrayOf(0f, h / 2, 0f, h), floatArrayOf(0f, 0f, 0f, h / 2),
                floatArrayOf(0f, h / 2, w, h / 2))
            val mask = when (char) {
                '0' -> 0x3f; '1' -> 0x06; '2' -> 0x5b; '3' -> 0x4f; '4' -> 0x66
                '5' -> 0x6d; '6' -> 0x7d; '7' -> 0x07; '8' -> 0x7f; '9' -> 0x6f; else -> 0
            }
            segments.forEachIndexed { number, line ->
                if (mask and (1 shl number) != 0) drawLine(left + line[0], y + line[1], left + line[2], y + line[3], paint)
            }
            when (char) {
                '.' -> drawPoint(left + w / 2, y + h, paint)
                ':' -> { drawPoint(left + w / 2, y + h * .3f, paint); drawPoint(left + w / 2, y + h * .75f, paint) }
                '\'' -> drawLine(left + w * .65f, y, left + w * .5f, y + h * .2f, paint)
            }
        }
    }
    paint.color = Color.argb(105, 100, 36, 0)
    paint.strokeWidth *= 2.2f
    lettering()
    paint.strokeWidth /= 2.2f
    paint.color = tint
    lettering()
}
