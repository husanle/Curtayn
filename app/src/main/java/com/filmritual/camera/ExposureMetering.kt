package com.filmritual.camera

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class MeteringGrid(val side: Int, val luminance: FloatArray, val capturedAt: Long) {
    init { require(side > 0 && luminance.size == side * side) }
}

internal fun linearChannel(value: Int): Float {
    val gamma = value.coerceIn(0, 255) / 255f
    return if (gamma <= .04045f) gamma / 12.92f else ((gamma + .055f) / 1.055f).pow(2.4f)
}

/** Measures the unfiltered camera image; neither film curves nor UI overlays enter this calculation. */
internal fun meteredLuminance(mode: MeteringMode, grid: MeteringGrid, point: Pair<Float, Float>?): Float {
    val clean = grid.luminance.map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    if (mode == MeteringMode.WEIGHTED) return clean.sorted()[(clean.size * .98f).toInt().coerceAtMost(clean.lastIndex)]
    val spot = MeteringMode.SPOT.zones(point, 1).single()
    var sum = 0.0
    var weights = 0.0
    for (y in 0 until grid.side) for (x in 0 until grid.side) {
        val u = (x + .5f) / grid.side
        val v = (y + .5f) / grid.side
        val weight = when (mode) {
            MeteringMode.CENTER -> .10 + exp(-((u - .5).pow(2) + (v - .5).pow(2)) / .035)
            MeteringMode.SPOT -> if (u >= spot.left && u <= spot.right && v >= spot.top && v <= spot.bottom) 1.0 else 0.0
            else -> 1.0
        }
        sum += clean[y * grid.side + x] * weight
        weights += weight
    }
    if (weights > 0) return (sum / weights).toFloat()
    val (u, v) = point ?: (.5f to .5f)
    return clean[(v * grid.side).toInt().coerceIn(0, grid.side - 1) * grid.side + (u * grid.side).toInt().coerceIn(0, grid.side - 1)]
}

internal data class MeteringReading(val average: Float, val selected: Float, val ev: Float)

/** Relative luminance cancels the exposure already present in the preview. Never accumulate EV
 * corrections from a partly adjusted image: doing that makes the camera chase its own output. */
internal fun readMetering(mode: MeteringMode, grid: MeteringGrid, point: Pair<Float, Float>?): MeteringReading {
    val average = meteredLuminance(MeteringMode.AVERAGE, grid, null)
    val selected = if (mode == MeteringMode.AVERAGE) average else meteredLuminance(mode, grid, point)
    val ratio = average.coerceAtLeast(.002f) / selected.coerceAtLeast(.002f)
    val ev = when (mode) {
        MeteringMode.AVERAGE -> 0f
        // Allow highlights up to 4 times middle gray, reducing exposure only when necessary.
        MeteringMode.WEIGHTED -> minOf(0f, ln(ratio * 4f) / ln(2f))
        else -> ln(ratio) / ln(2f)
    }
    return MeteringReading(average, selected, ev)
}

internal fun meteringExposureIndex(ev: Float, step: Float, minimum: Int, maximum: Int): Int? {
    if (!ev.isFinite() || !step.isFinite() || step <= 0 || minimum > maximum) return null
    return (ev / step).roundToInt().coerceIn(minimum, maximum)
}
