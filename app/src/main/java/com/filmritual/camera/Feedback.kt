package com.filmritual.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val sampleRate = 32000

class Feedback(context: Context) {
    private val assets = context.assets
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val output = Executors.newSingleThreadExecutor()
    private val tracks = mutableMapOf<String, AudioTrack>()
    @Volatile private var closed = false
    private var lastTooth = 0L
    var intensity = .84f

    val mediaMuted get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    fun halfPress(haptics: Boolean) {
        if (haptics) pulse(Stroke.FOCUS)
    }

    fun ratchet(style: Advance, haptics: Boolean, audio: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTooth < 29) return
        lastTooth = now
        if (audio) play("advance-tooth", .80f, style.pitch, detentClick, 6)
        if (haptics) pulse(Stroke.RATCHET)
    }

    fun wind(style: Advance, haptics: Boolean, audio: Boolean) {
        if (audio) play("latch", 1f, style.pitch, listOf(Click(0, 420, .9), Click(14, 810, .56), Click(29, 275, .70)), 4)
        if (haptics) pulse(Stroke.LATCH)
    }

    fun detent(style: DialStyle, haptics: Boolean, audio: Boolean) {
        if (audio) play("iso", .89f, style.pitch, detentClick, 7)
        if (haptics) pulse(Stroke.DETENT)
    }

    fun exposed(shutter: Shutter, haptics: Boolean, audio: Boolean) {
        val reflex = shutter == Shutter.FM2 || shutter == Shutter.AE1 || shutter == Shutter.PENTAX
        if (haptics) pulse(if (reflex) Stroke.MIRROR else Stroke.CURTAIN)
        if (audio) play(shutter.recording ?: shutter.name, 1f, 1f, shutter.clicks, shutter.ordinal)
    }

    private fun play(name: String, volume: Float, pitch: Float, clicks: List<Click>, seed: Int) {
        if (closed) return
        output.execute {
            if (closed) return@execute
            runCatching {
                val track = tracks.getOrPut(name) {
                    val pcm = runCatching { assets.open("sounds/" + name + ".wav").use { wavePcm(it.readBytes()) } }
                        .getOrElse { synth(clicks, seed) }
                    makeTrack(pcm)
                }
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
                check(track.reloadStaticData() == AudioTrack.SUCCESS)
                track.playbackRate = (sampleRate * pitch).roundToInt()
                track.setVolume(volume)
                track.play()
            }.onFailure { error ->
                tracks.remove(name)?.release()
                Log.w("CurtaynAudio", "Mechanical playback failed for " + name, error)
            }
        }
    }

    private fun makeTrack(pcm: ByteArray): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(maxOf(minimum, pcm.size)).build()
        try {
            check(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) == pcm.size)
            check(track.state == AudioTrack.STATE_INITIALIZED)
            return track
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    private fun pulse(stroke: Stroke) {
        if (!vibrator.hasVibrator()) return
        val primary = (stroke.force * intensity).coerceIn(0f, 1f)
        val effect = if (Build.VERSION.SDK_INT >= 31 &&
            vibrator.areAllPrimitivesSupported(stroke.first, stroke.second ?: stroke.first)) {
            VibrationEffect.startComposition().addPrimitive(stroke.first, primary).apply {
                stroke.second?.let { addPrimitive(it, primary * .79f, stroke.gap) }
            }.compose()
        } else {
            val unit = (primary * 255).roundToInt().coerceIn(1, 255)
            if (vibrator.hasAmplitudeControl()) {
                val durations = if (stroke.second == null) longArrayOf(stroke.duration) else longArrayOf(stroke.duration, stroke.gap.toLong(), 24L)
                val amplitudes = if (stroke.second == null) intArrayOf(unit) else intArrayOf(unit, 0, (unit * .78f).roundToInt())
                VibrationEffect.createWaveform(durations, amplitudes, -1)
            } else if (stroke.second == null) {
                VibrationEffect.createOneShot(stroke.duration, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0L, stroke.duration, stroke.gap.toLong(), 25L), -1)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(effect)
    }

    fun close() {
        closed = true
        vibrator.cancel()
        output.execute { tracks.values.forEach(AudioTrack::release); tracks.clear() }
        output.shutdown()
    }

    private enum class Stroke(val first: Int, val second: Int?, val force: Float, val gap: Int, val duration: Long) {
        FOCUS(VibrationEffect.Composition.PRIMITIVE_TICK, null, .38f, 0, 17),
        RATCHET(VibrationEffect.Composition.PRIMITIVE_TICK, null, .58f, 0, 22),
        DETENT(VibrationEffect.Composition.PRIMITIVE_CLICK, null, .57f, 0, 24),
        LATCH(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 29, 32),
        MIRROR(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_CLICK, 1.14f, 39, 38),
        CURTAIN(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK, .99f, 17, 29);
    }

    private companion object {
        val detentClick = listOf(Click(0, 1490, .80), Click(3, 570, .65), Click(10, 335, .28))
    }
}

internal fun wavePcm(wav: ByteArray): ByteArray {
    require(wav.size >= 44 && wav.decodeToString(0, 4) == "RIFF" && wav.decodeToString(8, 12) == "WAVE")
    val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var validFormat = false
    while (offset <= wav.size - 8) {
        val id = wav.decodeToString(offset, offset + 4)
        val size = input.getInt(offset + 4)
        val start = offset + 8
        require(size >= 0 && size <= wav.size - start)
        if (id == "fmt ") {
            validFormat = size >= 16 && input.getShort(start).toInt() == 1 &&
                input.getShort(start + 2).toInt() == 1 && input.getInt(start + 4) == sampleRate &&
                input.getShort(start + 14).toInt() == 16
        } else if (id == "data") {
            require(validFormat && size >= 2 && size % 2 == 0)
            return wav.copyOfRange(start, start + size)
        }
        offset = start + size + size % 2
    }
    error("No playable PCM in WAV")
}

private fun synth(clicks: List<Click>, seed: Int): ByteArray {
    val count = sampleRate * 120 / 1000
    val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
    val random = Random(seed)
    for (index in 0 until count) {
        val seconds = index.toDouble() / sampleRate
        val value = clicks.sumOf { click ->
            val local = seconds - click.offsetMs / 1000.0
            if (local < 0) 0.0 else {
                val ring = sin(2 * PI * local * click.frequency) * exp(-local * 123)
                val grit = (random.nextDouble() * 2 - 1) * exp(-local * 290)
                click.amplitude * (ring * .65 + grit * .35)
            }
        }
        out.putShort((value.coerceIn(-1.0, 1.0) * 30700).toInt().toShort())
    }
    return out.array()
}

class MotionHint(context: Context, private val onChange: (Boolean) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var previous = 9.81f
    private var smoothed = 0f
    private var shaky = false
    fun start() { sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { manager.unregisterListener(this); smoothed = 0f; shaky = false; onChange(false) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        val data = event.values
        val magnitude = sqrt(data[0] * data[0] + data[1] * data[1] + data[2] * data[2])
        val force = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) magnitude else abs(magnitude - previous)
        previous = magnitude
        smoothed = smoothed * .7f + force * .3f
        val next = if (shaky) smoothed > .45f else smoothed > 1.15f
        if (next != shaky) { shaky = next; onChange(next) }
    }
}
