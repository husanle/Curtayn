package com.filmritual.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.EGLSurface
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.random.Random

class LutRenderer(private val cubes: Map<Film, Cube>, private val onError: (Int, Throwable) -> Unit, private val onFrame: (Int) -> Unit) {
    private val worker = HandlerThread("film-color").apply { start() }
    private val handler = Handler(worker.looper)
    private val executor = Executor { handler.post(it) }
    private val outputs = linkedMapOf<SurfaceOutput, Pair<Int, EGLSurface>>()
    private val textures = mutableMapOf<Film, Int>()
    private val inputMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val identity = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private var gpu: Gpu? = null
    private var activeInput: SurfaceTexture? = null
    private var inputCount = 0
    private var closing = false
    @Volatile var film = FilmRender(Film.EKTAR, Film.EKTAR.iso)
    private var grainSeed = 0
    @Volatile internal var onLuminance: ((Int, MeteringGrid) -> Unit)? = null
    private var meteredAt = 0L
    private var meterReadbackFailed = false
    private var meterInterval = 450L

    internal fun requestMeteringSample(mode: MeteringMode) {
        handler.post {
            meteredAt = 0L
            meterInterval = if (mode == MeteringMode.SPOT) 250L else 450L
        }
    }

    fun effect(session: Int): CameraEffect {
        val processor = object : SurfaceProcessor {
            override fun onInputSurface(request: SurfaceRequest) = input(request, session)
            override fun onOutputSurface(output: SurfaceOutput) = output(output, session)
        }
        return object : CameraEffect(CameraEffect.PREVIEW, executor, processor, { onError(session, it) }) {}
    }

    private fun input(request: SurfaceRequest, session: Int) {
        try {
            check(!closing)
            val current = gpu ?: Gpu().also { gpu = it }
            current.use(current.buffer)
            val oes = texture(GL_TEXTURE_EXTERNAL_OES)
            val input = SurfaceTexture(oes)
            val surface = Surface(input)
            input.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            activeInput = input
            inputCount++
            input.setOnFrameAvailableListener({ source ->
                try {
                    current.use(current.buffer)
                    source.updateTexImage()
                    if (source === activeInput && !closing) {
                        source.getTransformMatrix(inputMatrix)
                        val selected = film
                        for ((output, binding) in outputs) {
                            if (binding.first != session) continue
                            val target = binding.second
                            output.updateTransformMatrix(outputMatrix, inputMatrix)
                            current.use(target)
                            glViewport(0, 0, output.size.width, output.size.height)
                            draw(current, current.previewProgram, GL_TEXTURE_EXTERNAL_OES, oes, outputMatrix, selected,
                                minOf(output.size.width, output.size.height))
                            check(eglSwapBuffers(current.display, target)) { "取景输出中断" }
                            onFrame(session)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (onLuminance != null && !meterReadbackFailed && now - meteredAt >= meterInterval) {
                                meteredAt = now
                                val sampled = runCatching { sampleLuminance(current, oes, outputMatrix, selected, now) }
                                sampled.onSuccess { onLuminance?.invoke(session, it) }.onFailure {
                                    meterReadbackFailed = true
                                    glBindFramebuffer(GL_FRAMEBUFFER, 0)
                                    for (attempt in 0 until 8) { if (glGetError() == GL_NO_ERROR) break }
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    source.setOnFrameAvailableListener(null)
                    onError(session, error)
                }
            }, handler)
            request.provideSurface(surface, executor) {
                if (input === activeInput) activeInput = null
                current.use(current.buffer)
                input.setOnFrameAvailableListener(null)
                surface.release()
                input.release()
                deleteTextures(oes)
                inputCount--
                finishClose()
            }
        } catch (error: Exception) {
            request.willNotProvideSurface()
            onError(session, error)
        }
    }

    private fun sampleLuminance(current: Gpu, input: Int, matrix: FloatArray, selected: FilmRender, now: Long): MeteringGrid {
        current.use(current.buffer)
        val readback = current.meterReadback ?: MeterReadback().also { current.meterReadback = it }
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, readback.framebuffer)
            glViewport(0, 0, 32, 32)
            draw(current, current.previewProgram, GL_TEXTURE_EXTERNAL_OES, input, matrix, selected, 32, raw = true)
            readback.bytes.clear()
            glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, readback.bytes)
            check(glGetError() == GL_NO_ERROR)
            val values = FloatArray(32 * 32) { index ->
                // OpenGL reads bottom-up; metering points use the displayed top-left origin.
                val offset = ((31 - index / 32) * 32 + index % 32) * 4
                val r = linearChannel(readback.bytes.get(offset).toInt() and 255)
                val g = linearChannel(readback.bytes.get(offset + 1).toInt() and 255)
                val b = linearChannel(readback.bytes.get(offset + 2).toInt() and 255)
                .2126f * r + .7152f * g + .0722f * b
            }
            return MeteringGrid(32, values, now)
        } finally { glBindFramebuffer(GL_FRAMEBUFFER, 0) }
    }

    private class MeterReadback {
        val bytes: ByteBuffer = ByteBuffer.allocateDirect(32 * 32 * 4)
        private val target = texture(GL_TEXTURE_2D).also {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        }
        val framebuffer = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        init {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        }
    }

    private fun output(output: SurfaceOutput, session: Int) {
        if (closing) { output.close(); return }
        try {
            val current = gpu ?: Gpu().also { gpu = it }
            val surface = output.getSurface(executor) { remove(output); finishClose() }
            val eglSurface = eglCreateWindowSurface(current.display, current.config, surface, intArrayOf(EGL_NONE), 0)
            check(eglSurface != EGL_NO_SURFACE)
            outputs[output] = session to eglSurface
        } catch (error: Exception) {
            output.close()
            onError(session, error)
        }
    }

    fun render(jpeg: ByteArray, rotation: Int, selected: FilmRender, edge: FilmEdge?, imprint: DateImprint?, callback: (Result<Bitmap>) -> Unit) {
        executor.execute { callback(runCatching { renderJpeg(jpeg, rotation, selected, edge, imprint) }) }
    }

    @Suppress("DEPRECATION")
    private fun renderJpeg(jpeg: ByteArray, rotation: Int, selected: FilmRender, edge: FilmEdge?, imprint: DateImprint?): Bitmap {
        val decoder = checkNotNull(BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false))
        try {
            return renderRegions(decoder, PhotoOrientation(decoder.width, decoder.height, rotation), selected, edge, imprint)
        } finally {
            decoder.recycle()
        }
    }

    private fun renderRegions(decoder: BitmapRegionDecoder, orientation: PhotoOrientation, selected: FilmRender, edge: FilmEdge?, imprint: DateImprint?): Bitmap {
        val current = checkNotNull(gpu)
        current.use(current.buffer)
        val width = decoder.width
        val height = decoder.height
        val seed = ++grainSeed
        val max = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val strip = edge?.let { FilmStripLayout(orientation.width, orientation.height) }
        val tileSize = minOf(1024, max)
        check(tileSize > 0)
        val bytes = ByteBuffer.allocateDirect(minOf(tileSize, width) * minOf(tileSize, height) * 4)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val final = Bitmap.createBitmap(strip?.width ?: orientation.width, strip?.height ?: orientation.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(final)
            if (strip != null) canvas.drawFilmStrip(strip, edge)
            val canvasState = canvas.save()
            canvas.translate((orientation.shiftX + (strip?.photoLeft ?: 0)).toFloat(),
                (orientation.shiftY + (strip?.photoTop ?: 0)).toFloat())
            canvas.rotate(orientation.rotation.toFloat())
            glActiveTexture(GL_TEXTURE0)
            val framebuffer = IntArray(1).also { glGenFramebuffers(1, it, 0) }
            val input = texture(GL_TEXTURE_2D)
            val target = texture(GL_TEXTURE_2D)
            try {
                for (top in 0 until height step tileSize) for (left in 0 until width step tileSize) {
                    val tileWidth = minOf(tileSize, width - left)
                    val tileHeight = minOf(tileSize, height - top)
                    val region = Rect(left, top, left + tileWidth, top + tileHeight)
                    val source = checkNotNull(decoder.decodeRegion(region, options))
                    try {
                        glBindTexture(GL_TEXTURE_2D, input)
                        GLUtils.texImage2D(GL_TEXTURE_2D, 0, source, 0)
                    } finally {
                        source.recycle()
                    }
                    glBindTexture(GL_TEXTURE_2D, target)
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tileWidth, tileHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
                    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0])
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0)
                    check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
                    glViewport(0, 0, tileWidth, tileHeight)
                    draw(current, current.stillProgram, GL_TEXTURE_2D, input, identity, selected, minOf(width, height), left, top, seed)
                    bytes.clear()
                    glReadPixels(0, 0, tileWidth, tileHeight, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
                    check(glGetError() == GL_NO_ERROR) { "成片颜色处理失败" }
                    bytes.rewind()
                    val result = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                    try {
                        result.copyPixelsFromBuffer(bytes)
                        canvas.drawBitmap(result, left.toFloat(), top.toFloat(), null)
                    } finally {
                        result.recycle()
                    }
                }
            } finally {
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glDeleteFramebuffers(1, framebuffer, 0)
                deleteTextures(input, target)
            }
            canvas.restoreToCount(canvasState)
            imprint?.let { stamp ->
                val left = (strip?.photoLeft ?: 0).toFloat()
                val top = (strip?.photoTop ?: 0).toFloat()
                canvas.drawFilmDateImprint(stamp, android.graphics.RectF(left, top,
                    left + orientation.width, top + orientation.height), orientation.rotation)
            }
            return final
        } catch (error: Throwable) {
            final.recycle()
            throw error
        }
    }

    private fun draw(current: Gpu, program: Int, target: Int, image: Int, matrix: FloatArray, selected: FilmRender,
        shortSide: Int, left: Int = 0, top: Int = 0, seed: Int = 0, raw: Boolean = false) {
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, current.grainTexture)
        glActiveTexture(GL_TEXTURE1)
        val lutTexture = textures.getOrPut(selected.film) {
            val cube = cubes.getValue(selected.film)
            texture(GL_TEXTURE_3D).also {
                val data = ByteBuffer.allocateDirect(cube.rgb.size).apply { put(cube.rgb); rewind() }
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
                glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB8, cube.size, cube.size, cube.size, 0, GL_RGB, GL_UNSIGNED_BYTE, data)
                glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
            }
        }
        glBindTexture(GL_TEXTURE_3D, lutTexture)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(target, image)
        glUniform1i(glGetUniformLocation(program, "cameraImage"), 0)
        glUniform1i(glGetUniformLocation(program, "meterOnly"), if (raw) 1 else 0)
        glUniform1i(glGetUniformLocation(program, "lutImage"), 1)
        glUniform1i(glGetUniformLocation(program, "grainImage"), 2)
        glUniformMatrix4fv(glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        val grain = selected.grain
        glUniform2f(glGetUniformLocation(program, "grain"), grain.strength,
            (grain.size * shortSide / 1080f).coerceAtLeast(.65f))
        glUniform2f(glGetUniformLocation(program, "grainOrigin"), left.toFloat(), top.toFloat())
        glUniform1ui(glGetUniformLocation(program, "grainSeed"), seed)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    fun close() = executor.execute {
        closing = true
        finishClose()
    }

    private fun remove(output: SurfaceOutput) {
        outputs.remove(output)?.let { binding ->
            gpu?.let { current -> current.use(current.buffer); eglDestroySurface(current.display, binding.second) }
        }
        output.close()
    }

    private fun finishClose() {
        if (!closing || inputCount > 0 || outputs.isNotEmpty()) return
        gpu?.close()
        worker.quitSafely()
    }

    private class Gpu {
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        val config = run {
            check(eglInitialize(display, IntArray(1), 0, IntArray(1), 0))
            val matches = arrayOfNulls<android.opengl.EGLConfig>(1)
            val size = IntArray(1)
            check(eglChooseConfig(display, intArrayOf(EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT or EGL_WINDOW_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE), 0, matches, 0, 1, size, 0))
            checkNotNull(matches[0])
        }
        private val context = eglCreateContext(display, config, EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
        val buffer = eglCreatePbufferSurface(display, config, intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
        val previewProgram: Int
        val stillProgram: Int
        val grainTexture: Int
        var meterReadback: MeterReadback? = null
        init {
            use(buffer)
            previewProgram = program("#extension GL_OES_EGL_image_external_essl3 : require\n", "samplerExternalOES")
            stillProgram = program("", "sampler2D")
            grainTexture = createGrainTexture()
        }
        fun use(surface: EGLSurface) { check(eglMakeCurrent(display, surface, surface, context)) { "无法启用 OpenGL ES 3" } }
        fun close() {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            eglDestroySurface(display, buffer)
            eglDestroyContext(display, context)
            eglReleaseThread()
        }
    }

    companion object {
        private fun texture(target: Int): Int {
            val id = IntArray(1)
            glGenTextures(1, id, 0)
            glBindTexture(target, id[0])
            glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            if (target == GL_TEXTURE_3D) glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
            return id[0]
        }

        private fun createGrainTexture(): Int {
            val side = 512
            val random = Random(36035)
            val data = ByteBuffer.allocateDirect(side * side * 2)
            repeat(data.capacity()) {
                // The difference of two samples gives a neutral mean and fewer strong outliers.
                data.put((128 + random.nextInt(128) - random.nextInt(128)).toByte())
            }
            data.rewind()
            return texture(GL_TEXTURE_2D).also {
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, side, side, 0, GL_RG, GL_UNSIGNED_BYTE, data)
                glGenerateMipmap(GL_TEXTURE_2D)
            }
        }
        private fun deleteTextures(vararg ids: Int) = glDeleteTextures(ids.size, ids, 0)
        private fun shader(type: Int, source: String): Int = glCreateShader(type).also {
            glShaderSource(it, source)
            glCompileShader(it)
            val status = IntArray(1)
            glGetShaderiv(it, GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { glGetShaderInfoLog(it) }
        }
        private fun program(extension: String, sampler: String): Int {
            val vertex = shader(GL_VERTEX_SHADER, """#version 300 es
                uniform mat4 transform;
                out vec2 uv;
                void main() {
                    vec2 point = vec2(gl_VertexID & 1, gl_VertexID >> 1);
                    uv = (transform * vec4(point, 0., 1.)).xy;
                    gl_Position = vec4(point*2.-1., 0., 1.);
                }
            """.trimIndent())
            val fragment = shader(GL_FRAGMENT_SHADER, """#version 300 es
                EXTENSIONprecision highp float;
                precision highp int;
                uniform SAMPLER cameraImage;
                uniform highp sampler3D lutImage;
                uniform highp sampler2D grainImage;
                uniform vec2 grain;
                uniform vec2 grainOrigin;
                uniform highp uint grainSeed;
                uniform bool meterOnly;
                in vec2 uv;
                out vec4 pixel;
                void main() {
                    vec3 incoming = texture(cameraImage, uv).rgb;
                    if (meterOnly) { pixel = vec4(incoming, 1.); return; }
                    float edge = float(textureSize(lutImage, 0).x);
                    vec3 coord = (clamp(incoming, 0., 1.)*(edge-1.)+.5)/edge;
                    vec3 color = texture(lutImage, coord).rgb;
                    // Use photograph-wide coordinates so independently rendered tiles share one grain field.
                    vec2 position = gl_FragCoord.xy + grainOrigin;
                    float phase = float(grainSeed % 997u);
                    vec2 phaseOffset = phase * vec2(37.13, 19.31);
                    float textureSizePx = float(textureSize(grainImage, 0).x);
                    vec2 grainUv = (position / grain.y + phaseOffset) / textureSizePx;
                    vec2 fineUv = mat2(.96, .28, -.28, .96) * grainUv;
                    vec2 coarseUv = mat2(.8, .6, -.6, .8) * grainUv / 1.65 + vec2(.371, .137);
                    float fine = texture(grainImage, fineUv).r - 128. / 255.;
                    float coarse = texture(grainImage, coarseUv).g - 128. / 255.;
                    float luma = dot(color, vec3(.2126, .7152, .0722));
                    float weight = smoothstep(.025, .28, luma) * (1. - .94 * smoothstep(.56, .98, luma));
                    float density = grain.x * weight * (fine + .22 * coarse);
                    pixel = vec4(clamp(color * (1. + density), 0., 1.), 1.);
                }
            """.trimIndent().replace("EXTENSION", extension).replace("SAMPLER", sampler))
            return glCreateProgram().also {
                glAttachShader(it, vertex)
                glAttachShader(it, fragment)
                glLinkProgram(it)
                glDeleteShader(vertex)
                glDeleteShader(fragment)
                val status = IntArray(1)
                glGetProgramiv(it, GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { glGetProgramInfoLog(it) }
            }
        }
    }
}
