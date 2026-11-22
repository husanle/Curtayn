package com.filmritual.camera

import kotlin.math.roundToInt

internal fun compatibleFocals(base: Double, minimum: Float, maximum: Float, native: Set<Int>): Set<Int> {
    if (!base.isFinite() || base <= 0 || !minimum.isFinite() || !maximum.isFinite() || minimum <= 0 || maximum < minimum)
        return emptySet()
    return (native.map(Int::toDouble) + listOf(minimum.toDouble(), 1.0, 2.0, 3.0, 5.0, maximum.toDouble()).map { base * it })
        .filter { it / base >= minimum - .00001 && it / base <= maximum + .00001 }
        .map { it.roundToInt().coerceAtLeast(1) }.toSet()
}
