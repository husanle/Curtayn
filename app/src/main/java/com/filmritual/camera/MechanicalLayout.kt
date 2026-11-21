package com.filmritual.camera

import kotlin.math.hypot

// All distances are fractions of the deck width, so the controls keep their proportions.
internal class MechanicalLayout(advance: Advance, shutter: Shutter) {
    private data class Mount(val width: Float, val axisY: Float, val releaseX: Float = 0f, val releaseY: Float = 0f)

    private val mount = when (advance) {
        Advance.NIKON -> Mount(.500f, .178f, .510f, .106f)
        Advance.NIKON_SP -> Mount(.515f, .187f, .519f, .124f)
        Advance.LEICA -> Mount(.490f, .151f)
        Advance.ZEISS -> Mount(.500f, .209f, .499f, .106f)
        Advance.CANON -> Mount(.500f, .211f, .491f, .106f)
        Advance.CANON_AE1 -> Mount(.545f, .161f, .521f, .106f)
        Advance.PENTAX -> Mount(.480f, .160f, .555f, .106f)
        Advance.AGFA -> Mount(.550f, .155f)
    }
    val aspect = 1.8f
    val dialDiameter = .35f
    val dialX = .19f
    val dialY = .270f
    val pivotX = .862f
    val pivotY = mount.axisY
    val leverWidth = mount.width
    val coaxial = advance.coaxialShutter
    val shutterX = if (coaxial) pivotX else mount.releaseX
    val shutterY = if (coaxial) pivotY else mount.releaseY
    val shutterDiameter = when {
        advance == Advance.AGFA -> .142f
        coaxial -> .134f
        shutter == Shutter.AGFA -> .193f
        else -> .190f
    }

    fun canStartWinding(x: Float, y: Float): Boolean {
        val dialRight = dialX + dialDiameter / 2
        val overShutter = !coaxial && hypot(x - shutterX, y - shutterY) < shutterDiameter / 2 + .015f
        return x > dialRight + .014f && y >= pivotY - .065f && !overShutter
    }
}
