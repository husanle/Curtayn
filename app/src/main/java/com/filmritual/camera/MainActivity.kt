package com.filmritual.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("film-ritual", MODE_PRIVATE) }
    lateinit var preview: PreviewView
        private set
    private var pipeline: CameraPipeline? = null
    private lateinit var feedback: Feedback
    private lateinit var motion: MotionHint
    private var feedbackPreview: Job? = null
    var camera by mutableStateOf(CameraState())
        private set
    var roll by mutableStateOf(Roll(Film.EKTAR))
        private set
    private var independentFinder by mutableStateOf<Finder?>(null)
    var shutter by mutableStateOf(Shutter.FM2)
        private set
    var brandLocked by mutableStateOf(true)
        private set
    private var independentAdvance by mutableStateOf<Advance?>(null)
    val advance get() = if (brandLocked) shutter.matchingAdvance else independentAdvance ?: shutter.matchingAdvance
    val finder get() = if (brandLocked) shutter.matchingFinder else independentFinder ?: shutter.matchingFinder
    var dial by mutableStateOf(DialStyle.BLACK)
        private set
    var filmIso by mutableIntStateOf(100)
        private set
    var haptics by mutableStateOf(true)
        private set
    var hapticStrength by mutableStateOf(.84f)
        private set
    var filmEdges by mutableStateOf(false)
        private set
    var audio by mutableStateOf(true)
        private set
    var motionEnabled by mutableStateOf(true)
        private set
    var shaky by mutableStateOf(false)
        private set
    var permitted by mutableStateOf(false)
        private set
    var saved by mutableStateOf<SavedFrame?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var flash by mutableStateOf(0)
        private set
    var picker by mutableStateOf<Film?>(null)
        private set
    var films by mutableStateOf(Film.originals)
        private set
    var tiles by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    private var cubes: Map<Film, Cube>? = null
    private var meteringSpot: Pair<Float, Float>? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permitted = allowed
        if (allowed) connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        feedback = Feedback(this)
        motion = MotionHint(this) { shaky = it }
        independentFinder = preferences.getString("finder", null)?.let { enumOr(it, Finder.LEICA) }
        shutter = enumOr(preferences.getString("shutter", null), Shutter.FM2)
        brandLocked = preferences.getBoolean("brandLocked", true)
        independentAdvance = preferences.getString("advance", null)?.let { enumOr(it, Advance.NIKON) }
        dial = enumOr(preferences.getString("dial", null), DialStyle.BLACK)
        haptics = preferences.getBoolean("haptics", true)
        hapticStrength = preferences.getFloat("hapticStrength", .84f).coerceIn(.35f, 1f)
        filmEdges = preferences.getBoolean("filmEdges", false)
        feedback.intensity = hapticStrength
        audio = preferences.getBoolean("audio", true)
        motionEnabled = preferences.getBoolean("motion", true)
        permitted = hasCamera()
        setContent { CameraScreen(this) }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val catalog = assets.open("films/catalog.tsv").reader().use(Film::readCatalog)
                    val luts = catalog.associateWith { film ->
                        film.look?.let(Cube::fromLook)
                            ?: assets.open("luts/" + film.key + ".cube").reader().use(Cube::parse)
                    }
                    luts to Film.originals.map(Film::key).zip(atlas("materials/film-atlas.png")).toMap()
                }
            }
            loaded.onSuccess { (parsed, filmTiles) ->
                films = parsed.keys.toList()
                val oldKey = preferences.getString("film", null)
                val film = films.firstOrNull { it.key == oldKey } ?: when (oldKey) {
                    "KODACHROME" -> Film.KODACHROME
                    "DELTA100" -> Film.DELTA100
                    "DELTA3200" -> Film.DELTA3200
                    else -> Film.EKTAR
                }
                roll = Roll(film, preferences.getInt("frames", 0), if (preferences.getBoolean("armed", false)) Phase.ARMED else Phase.NEEDS_WIND)
                filmIso = preferences.getInt("grainIso", film.iso).coerceIn(25, 3200)
                cubes = parsed
                tiles = filmTiles
                connect()
            }
                .onFailure { notice = "胶卷资料读取失败：" + (it.message ?: "未知错误") }
        }
        if (!permitted && !preferences.getBoolean("asked", false)) permission()
    }

    private fun atlas(name: String): List<Bitmap> {
        val sheet = checkNotNull(assets.open(name).use { BitmapFactory.decodeStream(it) })
        val halfWidth = sheet.width / 2
        val halfHeight = sheet.height / 2
        return List(4) { index -> Bitmap.createBitmap(sheet, (index % 2) * halfWidth, (index / 2) * halfHeight, halfWidth, halfHeight) }
            .also { sheet.recycle() }
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun permission() {
        if (preferences.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            preferences.edit().putBoolean("asked", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun connect() {
        val luts = cubes ?: return
        if (!permitted || pipeline != null) return
        pipeline = CameraPipeline(this, this, preview, luts) { camera = it }.also { cameraPipeline ->
            cameraPipeline.setFilm(currentRender())
            preview.doOnLayout { cameraPipeline.start() }
        }
    }

    override fun onResume() {
        super.onResume()
        permitted = hasCamera()
        connect()
        if (motionEnabled) motion.start()
    }
    override fun onPause() { feedbackPreview?.cancel(); motion.stop(); super.onPause() }
    override fun onDestroy() { pipeline?.close(); feedback.close(); super.onDestroy() }

    fun chooseLens(equivalent: Int) { meteringSpot = null; notice = null; pipeline?.switchTo(equivalent) }
    fun focus(x: Float, y: Float) {
        if (preview.width <= 0 || preview.height <= 0) return
        meteringSpot = x / preview.width to y / preview.height
        pipeline?.focus(x, y)
    }
    fun preFocus() {
        val (x, y) = meteringSpot ?: (.5f to .5f)
        pipeline?.focus(x * preview.width, y * preview.height)
        feedback.halfPress(haptics)
    }
    fun chooseFinder(value: Finder) {
        if (brandLocked) {
            chooseShutter(value.defaultShutter)
        } else {
            independentFinder = value
            preferences.edit().putString("finder", value.name).apply()
        }
    }
    fun chooseShutter(value: Shutter) { shutter = value; preferences.edit().putString("shutter", value.name).apply() }
    fun chooseAdvance(value: Advance) {
        if (brandLocked) {
            if (value != advance) chooseShutter(value.defaultShutter)
        } else {
            independentAdvance = value
            preferences.edit().putString("advance", value.name).apply()
        }
    }

    fun lockBrand(locked: Boolean) {
        if (locked == brandLocked) return
        if (!locked && independentAdvance == null) independentAdvance = advance
        if (!locked && independentFinder == null) independentFinder = finder
        brandLocked = locked
        preferences.edit().putBoolean("brandLocked", locked).putString("advance", independentAdvance?.name)
            .putString("finder", independentFinder?.name).apply()
    }
    fun chooseDial(value: DialStyle) { dial = value; preferences.edit().putString("dial", value.name).apply() }
    fun hearShutter() { confirmMediaVolume(); feedback.exposed(shutter, haptics, true) }
    fun hearAdvance() {
        confirmMediaVolume()
        feedbackPreview?.cancel()
        val selected = advance
        feedbackPreview = lifecycleScope.launch {
            repeat(selected.teeth - 1) {
                feedback.ratchet(selected, haptics, true)
                delay(53)
            }
            feedback.wind(selected, haptics, true)
        }
    }
    fun hearDial() { confirmMediaVolume(); feedback.detent(dial, haptics, true) }
    private fun confirmMediaVolume() {
        if (feedback.mediaMuted) Toast.makeText(this, "当前媒体音量为零", Toast.LENGTH_SHORT).show()
    }
    fun changeHapticStrength(value: Float) {
        hapticStrength = value.coerceIn(.35f, 1f)
        feedback.intensity = hapticStrength
    }
    fun saveHapticStrength() {
        preferences.edit().putFloat("hapticStrength", hapticStrength).apply()
        feedback.wind(advance, haptics, false)
    }
    fun chooseIso(value: Int) {
        if (roll.phase == Phase.CAPTURING || picker != null || value == filmIso) return
        filmIso = value
        preferences.edit().putInt("grainIso", value).apply()
        previewFilm()
        feedback.detent(dial, haptics, audio)
    }
    fun stepIso(direction: Int) = adjacentIso(filmIso, direction)?.let(::chooseIso)
    fun windTick() {
        if (picker == null && camera.ready && roll.phase == Phase.NEEDS_WIND) feedback.ratchet(advance, haptics, audio)
    }
    fun toggleAudio(value: Boolean) { audio = value; preferences.edit().putBoolean("audio", value).apply() }
    fun toggleFilmEdges(value: Boolean) {
        filmEdges = value
        preferences.edit().putBoolean("filmEdges", value).apply()
    }
    fun toggleHaptic(value: Boolean) { haptics = value; preferences.edit().putBoolean("haptics", value).apply() }
    fun toggleMotion(value: Boolean) {
        motionEnabled = value
        if (value) motion.start() else motion.stop()
        preferences.edit().putBoolean("motion", value).apply()
    }

    fun inspectFilm(film: Film) {
        if (cubes == null || roll.phase == Phase.CAPTURING) return
        picker = film
        previewFilm()
    }
    private fun currentRender(): FilmRender {
        val selected = picker ?: roll.film
        return FilmRender(selected, if (selected == roll.film) filmIso else selected.iso)
    }
    private fun previewFilm() { pipeline?.setFilm(currentRender()) }
    fun cancelPicker() { picker = null; previewFilm() }
    fun load() {
        val selected = picker ?: return
        if (selected != roll.film) {
            roll = Roll(selected)
            filmIso = selected.iso
            storeRoll()
        }
        cancelPicker()
        notice = null
    }
    fun wind() {
        if (picker != null || roll.phase != Phase.NEEDS_WIND || !camera.ready) return
        roll = roll.wind()
        storeRoll()
        feedback.wind(advance, haptics, audio)
        notice = null
    }
    fun shoot() {
        if (picker != null || !camera.ready || roll.phase != Phase.ARMED) return
        roll = roll.press()
        val style = shutter
        val useHaptics = haptics
        val useAudio = audio
        notice = null
        val edge = if (filmEdges && roll.film.hasPerforations) FilmEdge(roll.film, roll.frames + 1) else null
        val accepted = pipeline?.shoot(currentRender(), edge, {
            flash++
            feedback.exposed(style, useHaptics, useAudio)
        }, { result ->
            roll = roll.complete(result.isSuccess)
            storeRoll()
            result.onSuccess {
                saved = it
                notice = "${it.width} × ${it.height}"
            }.onFailure { notice = "保存失败，可重拍" }
        }) ?: false
        if (!accepted) roll = roll.complete(false)
    }

    fun openLast() {
        val image = saved ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(image.uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { notice = "请在系统相册的「过片」中查看。" }
    }
    fun openSource(film: Film) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(film.source))) }
    }
    private fun storeRoll() {
        preferences.edit().putString("film", roll.film.key).putInt("frames", roll.frames).putInt("grainIso", filmIso)
            .putBoolean("armed", roll.phase == Phase.ARMED).apply()
    }
    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
