package com.filmritual.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

@OptIn(ExperimentalCamera2Interop::class)
class CameraPipeline(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    private val view: PreviewView,
    private val cubes: Map<Film, Cube>,
    private val onState: (CameraState) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var state = CameraState()
    private var paths = emptyMap<Int, List<CameraPath>>()
    private var active: CameraPath? = null
    private var starting = false
    private var closed = false
    private var generation = 0
    private var lastReady: Int? = null
    private var fallbackPrimary: CameraPath? = null
    private var mainLens: Int? = null
    private var unavailable = emptySet<Int>()
    private var detached = false
    private var captureInProgress = false
    private var deviceRotation = android.view.Surface.ROTATION_0
    private val orientationListener = object : android.view.OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val rotation = captureRotation(orientation) ?: return
            if (deviceRotation == rotation) return
            deviceRotation = rotation
            if (!captureInProgress) capture?.targetRotation = rotation
        }
    }
    private var attempt = 0
    private var compatibleZoom = false
    private var zoomRequest = 0
    private var previewReady = false
    private var zoomPending = false
    private var diagnosticCatalog = ""
    private var diagnosticAt = 0L
    private val metering = PreviewMetering(view, main, { status ->
        if (!closed && state.metering != status) show(state.copy(metering = status))
    }, { ev ->
        if (!closed && state.meteringEv != ev) show(state.copy(meteringEv = ev))
    })
    @Volatile private var frameGeneration = -1
    private var soundRequired = false
    private val mandatorySound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val renderer = LutRenderer(cubes, { token, error -> main.execute {
        if (!closed && generation == token) show(state.copy(ready = false, message = error.message ?: "实时着色失败"))
    } }) { token ->
        if (token == frameGeneration) {
            frameGeneration = -1
            main.execute {
                if (!closed && generation == token && capture != null) {
                    previewReady = true
                    if (!zoomPending) {
                        if (state.current != null) lastReady = state.current
                        show(state.copy(ready = true, message = null))
                    }
                }
            }
        }
    }.apply {
        onLuminance = { token, grid -> main.execute {
            if (!closed && generation == token && !captureInProgress && !zoomPending) metering.onLuminance(grid)
        } }
    }

    fun setFilm(film: FilmRender) { renderer.film = film }

    fun setCompatibleZoom(enabled: Boolean) {
        if (captureInProgress || compatibleZoom == enabled) return
        compatibleZoom = enabled
        zoomRequest++
        updateAvailable()
        if (provider != null) switchTo(mainLens ?: 0, force = true)
    }

    private fun zoomFocals(): Set<Int> {
        val source = provider ?: return paths.keys
        val base = fallbackPrimary?.equivalent?.takeIf { it.isFinite() && it > 0 } ?: return paths.keys
        val info = runCatching { source.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull() ?: return paths.keys
        val range = info.zoomState.value ?: return paths.keys
        return compatibleFocals(base, range.minZoomRatio, range.maxZoomRatio, paths.keys)
    }

    private fun updateAvailable() {
        show(state.copy(available = if (compatibleZoom) zoomFocals() else paths.keys - unavailable))
    }

    private fun changeZoom(equivalent: Int): Boolean {
        val session = camera ?: return false
        val base = fallbackPrimary?.equivalent?.takeIf { it.isFinite() && it > 0 } ?: return false
        val range = session.cameraInfo.zoomState.value ?: return false
        val ratio = (equivalent / base).toFloat().coerceIn(range.minZoomRatio, range.maxZoomRatio)
        val request = ++zoomRequest
        val previous = state.current
        zoomPending = true
        show(state.copy(ready = false, message = "正在打开镜头…"))
        val future = session.cameraControl.setZoomRatio(ratio)
        future.addListener({
            if (closed || request != zoomRequest || session != camera) return@addListener
            zoomPending = false
            if (runCatching { future.get() }.isSuccess) {
                val actual = kotlin.math.round(base * ratio).toInt()
                if (previewReady) lastReady = actual
                show(state.copy(ready = previewReady, current = actual, message = null))
            } else show(state.copy(ready = previewReady, current = previous, message = "变焦失败"))
        }, main)
        return true
    }

    fun setMetering(mode: MeteringMode, target: Pair<Float, Float>?) {
        metering.set(mode, target)
        renderer.requestMeteringSample(mode)
        if (state.ready && !captureInProgress) metering.apply()
    }

    fun start() {
        if (starting || closed || view.viewPort == null) return
        starting = true
        deviceRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val task = ProcessCameraProvider.getInstance(context)
        task.addListener({
            if (closed) return@addListener
            runCatching { task.get() }.onSuccess {
                provider = it
                scan(it)
                switchTo(mainLens ?: 0)
            }.onFailure { show(state.copy(message = "无法打开相机")) }
        }, main)
    }

    private fun scan(source: ProcessCameraProvider) {
        val manager = context.getSystemService(CameraManager::class.java)
        val rear = source.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .map { Camera2CameraInfo.from(it).cameraId }
        val primary = runCatching { Camera2CameraInfo.from(source.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)).cameraId }.getOrNull() ?: rear.firstOrNull()
        val candidates = buildList {
            for (parent in rear) {
                val metadata = runCatching { manager.getCameraCharacteristics(parent) }.getOrNull() ?: continue
                val standalone = runCatching { toPath(metadata, parent, null) }.getOrNull()
                if (parent == primary) fallbackPrimary = standalone ?: CameraPath(parent, null, Double.NaN, Float.NaN)
                if (standalone != null) add(standalone)
                for (physical in metadata.physicalCameraIds) {
                    val info = runCatching { manager.getCameraCharacteristics(physical) }.getOrNull() ?: continue
                    runCatching { toPath(info, parent, physical, metadata) }.getOrNull()?.let(::add)
                }
            }
        }
        paths = lensPaths(candidates)
        mainLens = fallbackPrimary?.takeIf { it.equivalent.isFinite() }?.let { primaryPath ->
            paths.entries.firstOrNull { primaryPath in it.value }?.key
        }
        diagnosticCatalog = candidates.joinToString("\n") { "logical=${it.parent} physical=${it.physical ?: "auto"} eq=${"%.1f".format(Locale.US, it.equivalent)}mm focal=${it.focal}mm" }
        show(state.copy(diagnostics = diagnosticCatalog))
        updateAvailable()
    }

    private fun toPath(info: CameraCharacteristics, parent: String, physical: String?, fallback: CameraCharacteristics? = null): CameraPath? {
        val ownMap = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val streamMap = ownMap?.takeIf { !it.getOutputSizes(ImageFormat.JPEG).isNullOrEmpty() ||
            !it.getHighResolutionOutputSizes(ImageFormat.JPEG).isNullOrEmpty() }
            ?: fallback?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        if (streamMap.getOutputSizes(ImageFormat.JPEG).isNullOrEmpty() &&
            streamMap.getHighResolutionOutputSizes(ImageFormat.JPEG).isNullOrEmpty()) return null
        val sensor = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull { it.isFinite() && it > 0 } ?: return null
        val equivalent = 43.2666 * focal / hypot(sensor.width.toDouble(), sensor.height.toDouble())
        return CameraPath(parent, physical, equivalent, focal).takeIf { equivalent.isFinite() }
    }

    fun switchTo(equiv: Int, force: Boolean = false) {
        if (closed || captureInProgress || (!force && state.ready && state.current == equiv)) return
        val source = provider ?: return
        if (!force && compatibleZoom && active == fallbackPrimary && changeZoom(equiv)) return
        zoomRequest++
        val candidates = if (compatibleZoom) listOfNotNull(fallbackPrimary) else paths[equiv].orEmpty().takeUnless { equiv in unavailable }.orEmpty()
        if (candidates.isEmpty() && (state.ready || (equiv != mainLens && equiv != 0))) return
        attempt++
        val request = attempt
        fun tryNext(index: Int, highResolution: Boolean = true) {
            if (closed || request != attempt) return
            val path = candidates.getOrNull(index)
            if (path == null) {
                if (candidates.isNotEmpty()) unavailable = unavailable + equiv
                val previous = lastReady?.takeIf { it != equiv && it !in unavailable }
                if (previous != null) { switchTo(previous); return }
            }
            val selected = path ?: fallbackPrimary
            if (selected == null) { show(state.copy(ready = false, message = "找不到可用后置相机")); return }
            fun retry(message: String) {
                when {
                    highResolution -> tryNext(index, false)
                    path != null -> tryNext(index + 1)
                    else -> show(state.copy(message = message))
                }
            }
            frameGeneration = -1
            previewReady = false
            zoomPending = false
            camera = null
            metering.bind(null)
            capture = null
            active = null
            val token = ++generation
            show(state.copy(ready = false, current = path?.let { equiv }, available = state.available - unavailable,
                exposure = Exposure.UNKNOWN, stillSize = null, metering = MeteringStatus.WAITING, message = "正在打开镜头…"))
            val selector = CameraSelector.Builder().addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == selected.parent } }
                .apply { selected.physical?.let { setPhysicalCameraId(it) } }.build()
            val previewBuilder = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            selected.physical?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, captureRequest: android.hardware.camera2.CaptureRequest, result: TotalCaptureResult) {
                    val ae = when (result.get(CaptureResult.CONTROL_AE_STATE)) {
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> Exposure.LOW
                        CaptureResult.CONTROL_AE_STATE_CONVERGED, CaptureResult.CONTROL_AE_STATE_LOCKED -> Exposure.READY
                        CaptureResult.CONTROL_AE_STATE_SEARCHING, CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> Exposure.METERING
                        else -> Exposure.UNKNOWN
                    }
                    val crop = result.get(CaptureResult.SCALER_CROP_REGION)
                    val physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    @Suppress("DEPRECATION")
                    val physicalResults = result.physicalCameraResults
                    val outputResult = selected.physical?.let { physicalResults[it] } ?: result
                    val focal = outputResult.get(CaptureResult.LENS_FOCAL_LENGTH)
                    val actualZoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                    val exposureNs = outputResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val sensorIso = outputResult.get(CaptureResult.SENSOR_SENSITIVITY)
                    main.execute {
                        if (!closed && generation == token) {
                            if (ae != state.exposure) show(state.copy(exposure = ae))
                            if (!captureInProgress) metering.onFrame(crop,
                                outputResult.get(CaptureResult.CONTROL_AE_REGIONS) ?: result.get(CaptureResult.CONTROL_AE_REGIONS),
                                result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION))
                            if (android.os.SystemClock.elapsedRealtime() - diagnosticAt > 1200) {
                                diagnosticAt = android.os.SystemClock.elapsedRealtime()
                                show(state.copy(diagnostics = "mode=${if (compatibleZoom) "logical zoom" else "physical"}\nrequested=${selected.parent}/${selected.physical ?: "auto"}\nactivePhysical=${physicalId ?: "not reported"}\nphysicalResults=${physicalResults.keys.joinToString().ifBlank { "not reported" }}\nfocal=${focal ?: "not reported"}mm zoom=${actualZoom ?: 1f}\nexposureNs=$exposureNs sensorIso=$sensorIso stillRotation=$deviceRotation\n" + metering.diagnostics + diagnosticCatalog))
                            }
                        }
                    }
                }
            })
            val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val stillBuilder = ImageCapture.Builder().setJpegQuality(96)
                .setTargetRotation(deviceRotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAllowedResolutionMode(if (highResolution) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                        else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setResolutionFilter { sizes, _ -> sizes.sortedByDescending { it.width.toLong() * it.height } }
                    .build())
            selected.physical?.let { Camera2Interop.Extender(stillBuilder).setPhysicalCameraId(it) }
            val still = stillBuilder.build()
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(still).addEffect(renderer.effect(token)).build()
            frameGeneration = token
            runCatching {
                source.unbindAll()
                source.bindToLifecycle(lifecycle, selector, group)
            }.onSuccess {
                camera = it
                val physicalLimit = selected.physical?.let { id ->
                    runCatching {
                        context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                            .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
                    }.getOrNull()
                }
                metering.bind(it, physicalLimit)
                active = path
                capture = still
                show(state.copy(stillSize = still.resolutionInfo?.resolution,
                    photoRotation = it.cameraInfo.getSensorRotationDegrees(view.display?.rotation ?: android.view.Surface.ROTATION_0)))
                updateAvailable()
                if (compatibleZoom) changeZoom(equiv.takeIf { it > 0 } ?: mainLens ?: 1)
                soundRequired = CameraInfo.mustPlayShutterSound()
                handler.postDelayed({
                    if (!closed && generation == token && !state.ready) {
                        retry("相机未输出画面，请重新打开应用")
                    }
                }, 4500L)
            }.onFailure {
                retry("此设备不支持当前相机组合")
            }
        }
        tryNext(0)
    }

    fun focus(x: Float, y: Float) {
        if (!state.ready || captureInProgress || view.width == 0 || view.height == 0) return
        runCatching {
            val point = view.meteringPointFactory.createPoint(x.coerceIn(0f, view.width.toFloat()), y.coerceIn(0f, view.height.toFloat()))
            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        }
    }

    fun shoot(film: FilmRender, edge: FilmEdge?, imprint: DateImprint?, onExposed: () -> Unit, onDone: (Result<SavedFrame>) -> Unit): Boolean {
        val session = capture ?: return false
        if (!state.ready || captureInProgress || closed) return false
        captureInProgress = true
        val path = active
        val equivalent = state.current
        val required = soundRequired
        fun done(result: Result<SavedFrame>) = main.execute {
            captureInProgress = false
            if (!closed) capture?.targetRotation = deviceRotation
            if (!closed) onDone(result)
            if (detached) release()
        }
        try {
            session.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureStarted() {
                    main.execute {
                        if (required) mandatorySound.play(MediaActionSound.SHUTTER_CLICK)
                        if (!closed) onExposed()
                    }
                }
                override fun onCaptureSuccess(image: ImageProxy) {
                    val captured = runCatching { image.use(::readJpeg) }
                    val frame = captured.getOrElse { done(Result.failure(it)); return }
                    renderer.render(frame.bytes, frame.rotation, film, edge, imprint) { result ->
                        io.execute {
                            done(result.mapCatching { filtered ->
                                try { save(filtered, frame.exif, equivalent, path, film) } finally { filtered.recycle() }
                            })
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) { done(Result.failure(exception)) }
            })
        } catch (error: Exception) {
            captureInProgress = false
            return false
        }
        return true
    }


    private fun readJpeg(image: ImageProxy): CapturedJpeg {
        require(image.format == ImageFormat.JPEG) { "相机没有返回 JPEG" }
        val buffer = image.planes.first().buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return CapturedJpeg(bytes, image.imageInfo.rotationDegrees,
            runCatching { ExifInterface(bytes.inputStream()) }.getOrNull())
    }

    private fun save(bitmap: Bitmap, original: ExifInterface?, equivalent: Int?, path: CameraPath?, look: FilmRender): SavedFrame {
        val name = "Film_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val temp = File.createTempFile("film", ".jpg", context.cacheDir)
        val resolver = context.contentResolver
        var pending: Uri? = null
        try {
            FileOutputStream(temp).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            val exif = ExifInterface(temp)
            val tags = listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE)
            for (tag in tags) original?.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            val appName = context.getString(R.string.app_name)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, appName)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, appName + ": " + look.film.brand + " " +
                look.film.label + " / simulated grain ISO " + look.iso)
            if (equivalent != null && path != null) {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, equivalent.toString())
                if (original?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) == null)
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, (path.focal * 1000).toInt().toString() + "/1000")
            }
            exif.saveAttributes()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/过片")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            pending = uri
            checkNotNull(resolver.openOutputStream(uri)).use { output -> temp.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            pending = null
            val thumbSize = minOf(140, bitmap.width, bitmap.height)
            val square = android.media.ThumbnailUtils.extractThumbnail(bitmap, thumbSize, thumbSize)
            return SavedFrame(uri, square, bitmap.width, bitmap.height)
        } finally {
            pending?.let { runCatching { resolver.delete(it, null, null) } }
            temp.delete()
        }
    }

    private fun show(updated: CameraState) { state = updated; onState(updated) }

    fun close() {
        closed = true
        orientationListener.disable()
        detached = true
        metering.close()
        provider?.unbindAll()
        handler.removeCallbacksAndMessages(null)
        if (!captureInProgress) release()
    }

    private fun release() { renderer.close(); mandatorySound.release(); io.shutdown() }

    private data class CapturedJpeg(val bytes: ByteArray, val rotation: Int, val exif: ExifInterface?)
}

enum class Exposure { UNKNOWN, METERING, READY, LOW }
data class CameraState(val ready: Boolean = false, val current: Int? = null, val available: Set<Int> = emptySet(),
    val message: String? = null, val exposure: Exposure = Exposure.UNKNOWN, val stillSize: Size? = null,
    val metering: MeteringStatus = MeteringStatus.WAITING, val meteringEv: Float? = null,
    val photoRotation: Int = 90, val diagnostics: String = "")
data class SavedFrame(val uri: Uri, val thumb: Bitmap, val width: Int, val height: Int)
