package com.filmritual.camera

internal class PhotoOrientation(sourceWidth: Int, sourceHeight: Int, val rotation: Int) {
    init { require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) }

    val width = if (rotation % 180 == 0) sourceWidth else sourceHeight
    val height = if (rotation % 180 == 0) sourceHeight else sourceWidth
    val shiftX = if (rotation == 90 || rotation == 180) width else 0
    val shiftY = if (rotation == 180 || rotation == 270) height else 0
}
