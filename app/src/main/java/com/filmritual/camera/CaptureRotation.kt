package com.filmritual.camera

/** Maps physical device orientation to Android Surface rotation, even with portrait UI locked. */
internal fun captureRotation(orientation: Int): Int? = when (orientation) {
    in 45..134 -> 3
    in 135..224 -> 2
    in 225..314 -> 1
    in 0..44, in 315..359 -> 0
    else -> null
}
