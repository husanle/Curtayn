package com.filmritual.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.view.PreviewView
import java.util.concurrent.Executor

@OptIn(ExperimentalCamera2Interop::class)
internal class PreviewMetering(
    private val view: PreviewView,
    private val main: Executor,
    private val onStatus: (MeteringStatus) -> Unit,
    private val onExposure: (Float?) -> Unit
) {
    private data class Binding(val camera: Camera, val control: Camera2CameraControl, val sensor: Rect?, val limit: Int)

    private var binding: Binding? = null
    private var mode = MeteringMode.WEIGHTED
    private var target: Pair<Float, Float>? = null
    private var crop: Rect? = null
    private var applied = emptyList<MeteringRectangle>()
    private var version = 0
    private var dirty = true
    private var highlights = emptyList<MeteringZone>()
    private var nativeAccepted = false
    private var nativeRejected = false
    private var nativeSentAt = 0L
    private var softwareAccepted = false
    private var exposurePending = false
    private var exposureSerial = 0
    private val exposurePlan = MeteringExposurePlan()
    private var wantedIndex: Int? = null
    private var softwareRejected = false
    private var sampleFailure = false
    private var lastGridAt = 0L
    private var boundAt = 0L
    private var reportedIndex: Int? = null
    private var lastReading: MeteringReading? = null
    private var latestGrid: MeteringGrid? = null
    private var actualRegions = 0
    val diagnostics get() = "metering=$mode maxAeRegions=${binding?.limit} requestedRegions=${applied.size} returnedRegions=$actualRegions nativeConfirmed=$nativeAccepted\n" +
        "meterAverage=${lastReading?.average} meterSelected=${lastReading?.selected} targetEv=${lastReading?.ev} actualEvIndex=$reportedIndex wantedEvIndex=$wantedIndex evPending=$exposurePending softwareConfirmed=$softwareAccepted\n"
    private val layoutChanges = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) dirty = true
    }

    init { view.addOnLayoutChangeListener(layoutChanges) }

    fun bind(camera: Camera?, physicalLimit: Int? = null) {
        version++
        crop = null
        applied = emptyList()
        highlights = emptyList()
        nativeAccepted = false
        nativeRejected = false
        nativeSentAt = 0L
        softwareAccepted = false
        exposurePending = false
        exposureSerial++
        exposurePlan.select(mode)
        wantedIndex = null
        softwareRejected = false
        sampleFailure = false
        lastGridAt = 0L
        boundAt = SystemClock.elapsedRealtime()
        reportedIndex = null
        lastReading = null
        latestGrid = null
        onExposure(null)
        binding = camera?.let {
            val info = Camera2CameraInfo.from(it.cameraInfo)
            val limit = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            Binding(it, Camera2CameraControl.from(it.cameraControl),
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                minOf(limit, physicalLimit ?: limit))
        }
        dirty = binding != null
        onStatus(MeteringStatus.WAITING)
    }

    fun set(mode: MeteringMode, target: Pair<Float, Float>?) {
        if (this.mode == mode && this.target == target && mode != MeteringMode.SPOT) return
        this.mode = mode
        this.target = target
        exposurePlan.select(mode)
        softwareAccepted = false
        nativeAccepted = false
        nativeRejected = false
        highlights = emptyList()
        dirty = true
        reportStatus()
        // Use a recent unfiltered preview immediately, without waiting for another GPU readback
        // or for a previous exposure request. The ratio calculation needs no assumed EV index.
        val now = SystemClock.elapsedRealtime()
        latestGrid?.takeIf { now - it.capturedAt in 0L..700L }?.let(::adjustExposure)
        if (mode == MeteringMode.AVERAGE && exposurePlan.desiredIndex == null) adjustExposure(null)
    }

    fun onFrame(resultCrop: Rect?, returnedRegions: Array<MeteringRectangle>?, exposureIndex: Int?) {
        if (crop != resultCrop) {
            crop = resultCrop
            dirty = true
        }
        val session = binding ?: return
        if (!sampleFailure && SystemClock.elapsedRealtime() - maxOf(boundAt, lastGridAt) > 2500) {
            sampleFailure = true
            softwareAccepted = false
            dirty = true
        }
        if (exposureIndex != null) {
            reportedIndex = exposureIndex
            val exposure = session.camera.cameraInfo.exposureState
            onExposure(if (exposure.isExposureCompensationSupported) exposureIndex * exposure.exposureCompensationStep.toFloat() else null)
        }
        actualRegions = returnedRegions?.size ?: 0
        if (applied.isNotEmpty()) {
            nativeAccepted = returnedRegions?.toList() == applied
            if (!nativeAccepted && nativeSentAt > 0 && SystemClock.elapsedRealtime() - nativeSentAt > 1400) nativeRejected = true
        }
        apply()
        reportStatus()
    }

    fun onLuminance(grid: MeteringGrid) {
        val session = binding ?: return
        lastGridAt = grid.capturedAt
        latestGrid = grid
        if (sampleFailure) { sampleFailure = false; dirty = true }
        if (mode == MeteringMode.WEIGHTED) {
            val values = List(64) { cell ->
                val left = cell % 8 * grid.side / 8
                val top = cell / 8 * grid.side / 8
                var sum = 0f
                var count = 0
                for (y in top until (cell / 8 + 1) * grid.side / 8) for (x in left until (cell % 8 + 1) * grid.side / 8) {
                    sum += grid.luminance[y * grid.side + x]; count++
                }
                if (count > 0) sum / count * 255f else 0f
            }
            val next = highlightZones(values, 8, 8, session.limit)
            if (next != highlights) { highlights = next; dirty = true }
        }
        adjustExposure(grid)
    }

    private fun adjustExposure(grid: MeteringGrid?) {
        val session = binding ?: return
        val exposure = session.camera.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        if (!exposure.isExposureCompensationSupported || softwareRejected || step <= 0) return
        if ((mode == MeteringMode.SPOT || mode == MeteringMode.AVERAGE) && exposurePlan.desiredIndex != null) return
        val reading = grid?.let { readMetering(mode, it, framePoint()) }
            ?: if (mode == MeteringMode.AVERAGE) MeteringReading(0f, 0f, 0f) else return
        val index = meteringExposureIndex(reading.ev, step,
            exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper) ?: return
        val next = exposurePlan.offer(index, step, SystemClock.elapsedRealtime(), exposurePending) ?: return
        lastReading = reading
        val current = reportedIndex ?: exposure.exposureCompensationIndex
        if (!exposurePending && next == current) {
            softwareAccepted = true
            reportStatus()
            return
        }
        exposurePending = true
        wantedIndex = next
        val serial = ++exposureSerial
        val requestedTarget = exposurePlan.revision
        softwareAccepted = false
        reportStatus()
        // CameraX supersedes its older exposure request. Older callbacks are ignored by serial,
        // so a quick second tap cannot be queued behind the first or reported as a failure.
        val future = session.camera.cameraControl.setExposureCompensationIndex(next)
        future.addListener({
            if (binding !== session || serial != exposureSerial) return@addListener
            exposurePending = false
            wantedIndex = null
            if (runCatching { future.get() }.isSuccess) {
                reportedIndex = next
                onExposure(next * step)
                softwareAccepted = requestedTarget == exposurePlan.revision
            }
            else { softwareAccepted = false; softwareRejected = true; dirty = true }
            reportStatus()
        }, main)
        view.postDelayed({
            if (binding === session && serial == exposureSerial && exposurePending) {
                exposureSerial++
                exposurePending = false
                wantedIndex = null
                softwareRejected = true
                softwareAccepted = false
                dirty = true
                reportStatus()
            }
        }, 4000)
    }

    private fun framePoint(): Pair<Float, Float>? {
        val point = target ?: return null
        val texture = textureChild(view) ?: return point
        if (texture.width <= 0 || texture.height <= 0) return point
        // Map through the actual TextureView content transform, including rotation, fit and
        // mirroring. This is the very buffer sampled by the renderer, not sensor coordinates.
        val viewToGlobal = Matrix().also { view.transformMatrixToGlobal(it) }
        val textureToGlobal = Matrix().also { texture.transformMatrixToGlobal(it) }
        val globalToTexture = Matrix()
        val content = texture.getTransform(Matrix())
        val inverseContent = Matrix()
        if (!textureToGlobal.invert(globalToTexture) || !content.invert(inverseContent)) return point
        val mapped = floatArrayOf(point.first * view.width, point.second * view.height)
        viewToGlobal.mapPoints(mapped)
        globalToTexture.mapPoints(mapped)
        inverseContent.mapPoints(mapped)
        return (mapped[0] / texture.width).coerceIn(0f, 1f) to (mapped[1] / texture.height).coerceIn(0f, 1f)
    }

    private fun textureChild(node: android.view.View): android.view.TextureView? {
        if (node is android.view.TextureView) return node
        if (node is android.view.ViewGroup) for (index in 0 until node.childCount) {
            textureChild(node.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun reportStatus() {
        val session = binding ?: return
        onStatus(when {
            softwareAccepted -> MeteringStatus.COMPENSATED
            nativeAccepted -> MeteringStatus.CUSTOM
            (nativeRejected || session.limit == 0) &&
                (!session.camera.cameraInfo.exposureState.isExposureCompensationSupported || softwareRejected || sampleFailure) -> MeteringStatus.SYSTEM
            else -> MeteringStatus.WAITING
        })
    }

    fun apply() {
        val session = binding ?: return
        if (!dirty) return
        // A single exposure loop controls all four modes. Do not run an independent hardware
        // region loop against the image-based EV loop; drivers often ignore those regions.
        if (session.camera.cameraInfo.exposureState.isExposureCompensationSupported && !softwareRejected && !sampleFailure) {
            if (applied.isNotEmpty()) session.control.clearCaptureRequestOptions()
            applied = emptyList()
            nativeAccepted = false
            dirty = false
            reportStatus()
            return
        }
        val sensor = session.sensor
        val zones = mode.zones(null, session.limit)
        val requested = if (sensor == null || zones.isEmpty()) emptyList() else regions(sensor, session.limit) ?: run {
            nativeRejected = true
            reportStatus()
            return
        }
        dirty = false
        if (requested == applied) {
            reportStatus()
            return
        }
        applied = requested
        nativeAccepted = false
        nativeSentAt = SystemClock.elapsedRealtime()
        val token = ++version
        val command = CaptureRequestOptions.Builder().apply {
            if (requested.isNotEmpty()) setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, requested.toTypedArray())
        }.build()
        reportStatus()
        val future = session.control.setCaptureRequestOptions(command)
        future.addListener({
            if (token == version) {
                if (runCatching { future.get() }.isSuccess) {
                    reportStatus()
                } else {
                    applied = emptyList()
                    session.control.clearCaptureRequestOptions()
                    nativeRejected = true
                    reportStatus()
                }
            }
        }, main)
    }

    private fun viewFrame(sensor: Rect): RectF? {
        val transform = view.sensorToViewTransform ?: return null
        val bounds = Rect(sensor)
        crop?.let { bounds.intersect(it) }
        val frame = RectF(bounds)
        transform.mapRect(frame)
        return frame.takeIf { it.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat()) }
    }

    private fun regions(sensor: Rect, limit: Int): List<MeteringRectangle>? {
        val transform = view.sensorToViewTransform ?: return null
        val inverse = Matrix()
        if (!transform.invert(inverse) || view.width <= 0 || view.height <= 0) return null
        val bounds = Rect(sensor)
        crop?.let { bounds.intersect(it) }
        val frame = viewFrame(sensor) ?: return null
        val point = target?.let { (x, y) ->
            ((x * view.width - frame.left) / frame.width()) to ((y * view.height - frame.top) / frame.height())
        }
        val zones = if (mode == MeteringMode.WEIGHTED && highlights.isNotEmpty()) highlights.take(limit) else mode.zones(point, limit)
        return zones.mapNotNull { region ->
            val inView = RectF(frame.left + frame.width() * region.left, frame.top + frame.height() * region.top,
                frame.left + frame.width() * region.right, frame.top + frame.height() * region.bottom)
            inverse.mapRect(inView)
            val rect = Rect().also { inView.round(it) }
            if (rect.intersect(bounds)) MeteringRectangle(rect, region.weight) else null
        }
    }

    fun close() {
        bind(null)
        view.removeOnLayoutChangeListener(layoutChanges)
    }
}
