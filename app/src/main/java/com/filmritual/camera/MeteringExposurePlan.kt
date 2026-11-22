package com.filmritual.camera

import kotlin.math.abs

/** A tap is an absolute new target, even while the previous camera request is unfinished.
 * Spot holds that reading until another tap; continuous modes filter scene changes. */
internal class MeteringExposurePlan {
    var mode = MeteringMode.WEIGHTED
        private set
    var revision = 0
        private set
    var desiredIndex: Int? = null
        private set
    private var awaitingReading = true
    private var candidate: Int? = null
    private var candidateCount = 0
    private var lastRequestAt = 0L

    fun select(mode: MeteringMode) {
        this.mode = mode
        revision++
        desiredIndex = null
        awaitingReading = true
        candidate = null
        candidateCount = 0
    }

    fun offer(index: Int, step: Float, now: Long, busy: Boolean): Int? {
        if (awaitingReading) return accept(index, now)
        if (mode == MeteringMode.SPOT || mode == MeteringMode.AVERAGE || busy || now - lastRequestAt < 900L) return null
        val previous = desiredIndex ?: return accept(index, now)
        // Ignore small changes from quantization, tone mapping and preview noise.
        if (abs(index.toLong() - previous) * step < .5f) {
            candidate = null
            candidateCount = 0
            return null
        }
        if (candidate == index) candidateCount++ else { candidate = index; candidateCount = 1 }
        return if (candidateCount >= 2) accept(index, now) else null
    }

    private fun accept(index: Int, now: Long): Int {
        awaitingReading = false
        desiredIndex = index
        lastRequestAt = now
        candidate = null
        candidateCount = 0
        return index
    }
}
