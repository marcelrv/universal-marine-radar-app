package com.marineyachtradar.mayara.ui.radar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.SpokeData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for the full-screen polar radar canvas.
 *
 * ## Texture layout
 * A 512×512 [GL_LUMINANCE][GLES20.GL_LUMINANCE] texture stores the radar sweep:
 *   - Column index  = spoke angle, mapped by `angle * TEXTURE_SIZE / spokesPerRevolution`
 *   - Row index 0   = radar center (nearest range)
 *   - Row index 511 = farthest range
 *   - Pixel value   = radar intensity 0–255
 *
 * ## Palette
 * A 256×1 [GL_RGBA][GLES20.GL_RGBA] 1-D lookup-table texture maps intensity → colour.
 * Four palettes are supported: [ColorPalette.GREEN], [ColorPalette.YELLOW],
 * [ColorPalette.MULTI_COLOR], [ColorPalette.NIGHT_RED].
 *
 * ## Rendering pipeline
 * A full-screen quad ([TRIANGLE_STRIP]) is drawn every frame.  The fragment shader
 * converts each fragment's screen position to polar coordinates (dist, angle), samples
 * the radar texture, then maps intensity through the palette LUT.
 *
 * ## Thread safety
 * [updateSpoke] is called from the spoke-collection coroutine (background thread).
 * The texture buffer is guarded by [textureLock]; the dirty flag uses [AtomicBoolean].
 * All actual GL calls happen on the GL thread ([onDrawFrame], [onSurfaceCreated]).
 *
 * All public setters are safe to call from any thread; changes take effect on the next frame.
 */
class RadarGLRenderer : GLSurfaceView.Renderer {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        const val TEXTURE_SIZE = 512

        // GLSL ES 1.0 vertex shader — full-screen quad, no projection needed.
        private const val VERTEX_SHADER = """
attribute vec4 a_Position;
void main() {
    gl_Position = a_Position;
}
"""

        // GLSL ES 1.0 fragment shader — polar projection with palette LUT.
        private const val FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D u_Radar;
uniform sampler2D u_Palette;
uniform vec2 u_Center;       // pan offset in [−0.5..0.5] normalized units
uniform vec2 u_Resolution;   // viewport size in pixels

const float PI = 3.14159265;
const vec4 BACKGROUND = vec4(0.043, 0.047, 0.063, 1.0); // #0B0C10

void main() {
    // Convert fragment position to [-0.5 .. 0.5] centered coordinates.
    vec2 uv = gl_FragCoord.xy / u_Resolution - 0.5;
    // Correct for aspect ratio so the radar circle isn't stretched.
    float aspect = u_Resolution.x / u_Resolution.y;
    vec2 radarPos = vec2(uv.x * aspect, uv.y) - u_Center;

    float dist = length(radarPos);
    // Discard fragments outside the radar circle (radius = 0.5 in corrected space).
    if (dist > 0.5) {
        gl_FragColor = BACKGROUND;
        return;
    }

    // v = 0 at outer edge, 1 at center.  Row 0 in texture = center, so we flip.
    float v = 1.0 - (dist / 0.5);

    // atan(x, y): angle measured clockwise from North (y-up), result in [-PI..PI].
    float angle = atan(radarPos.x, radarPos.y);
    // Map angle to [0..1], starting at North.
    float u = fract(angle / (2.0 * PI) + 0.5);

    float intensity = texture2D(u_Radar, vec2(u, v)).r;
    gl_FragColor = texture2D(u_Palette, vec2(intensity, 0.5));
}
"""
    }

    // -----------------------------------------------------------------------
    // GL handles (valid only on GL thread)
    // -----------------------------------------------------------------------

    private var programHandle = 0
    private var positionHandle = 0
    private var radarUniform = 0
    private var paletteUniform = 0
    private var centerUniform = 0
    private var resolutionUniform = 0
    private var radarTexture = 0
    private var paletteTexture = 0
    private var quadVbo = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    /** False until [onSurfaceCreated] completes without error. Guards all draw calls. */
    @Volatile private var glInitialized = false

    // -----------------------------------------------------------------------
    // Radar texture buffer (written from any thread, uploaded on GL thread)
    // -----------------------------------------------------------------------

    private val textureLock = Any()

    /** Flat 512×512 luminance buffer: [col + row * TEXTURE_SIZE] = intensity. */
    private val textureBuffer = ByteArray(TEXTURE_SIZE * TEXTURE_SIZE)

    private val textureDirty = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // Pan state
    // -----------------------------------------------------------------------

    @Volatile
    private var centerX = 0f

    @Volatile
    private var centerY = 0f

    // -----------------------------------------------------------------------
    // Palette state
    // -----------------------------------------------------------------------

    @Volatile
    private var activePalette = ColorPalette.GREEN

    private val paletteDirty = AtomicBoolean(true) // upload on first frame

    // -----------------------------------------------------------------------
    // Quad geometry (NDC full-screen: (-1,-1) .. (1,1))
    // -----------------------------------------------------------------------

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f,
            ))
            rewind()
        }

    // =======================================================================
    // Public API (callable from any thread)
    // =======================================================================

    /**
     * Write a single spoke into the radar texture buffer.
     *
     * @param spoke         Decoded spoke data from the WebSocket.
     * @param spokesPerRevolution Total number of spokes per 360° revolution.
     */
    fun updateSpoke(spoke: SpokeData, spokesPerRevolution: Int) {
        val col = computeColumn(spoke.angle, spokesPerRevolution)
        synchronized(textureLock) {
            writeColumn(col, spoke.data)
        }
        textureDirty.set(true)
    }

    /**
     * Compute the texture column index for a given spoke angle.
     * Extracted for unit-testability (no GL context needed).
     */
    fun computeColumn(angle: Int, spokesPerRevolution: Int): Int {
        if (spokesPerRevolution <= 0) return 0
        return ((angle.toLong() * TEXTURE_SIZE) / spokesPerRevolution)
            .toInt()
            .coerceIn(0, TEXTURE_SIZE - 1)
    }

    /**
     * Write spoke intensity bytes into a column of [textureBuffer].
     * Must be called while holding [textureLock].
     */
    fun writeColumn(col: Int, data: ByteArray) {
        val rows = minOf(data.size, TEXTURE_SIZE)
        for (row in 0 until rows) {
            textureBuffer[col + row * TEXTURE_SIZE] = data[row]
        }
        // Zero-pad remaining rows if spoke is shorter than texture height.
        for (row in rows until TEXTURE_SIZE) {
            textureBuffer[col + row * TEXTURE_SIZE] = 0
        }
    }

    /** Accumulate a pan delta expressed in normalised display units. */
    fun setCenterOffset(dx: Float, dy: Float) {
        centerX = (centerX + dx).coerceIn(-0.5f, 0.5f)
        centerY = (centerY + dy).coerceIn(-0.5f, 0.5f)
    }

    /** Reset radar display to center (double-tap). */
    fun resetCenter() {
        centerX = 0f
        centerY = 0f
    }

    /** Switch the active colour palette. */
    fun setPalette(palette: ColorPalette) {
        if (activePalette != palette) {
            activePalette = palette
            paletteDirty.set(true)
        }
    }

    // =======================================================================
    // GLSurfaceView.Renderer callbacks (GL thread only)
    // =======================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            initGL()
        } catch (e: Throwable) {
            Log.e("RadarGLRenderer", "GL initialisation failed — radar rendering unavailable", e)
            // Do NOT re-throw: an uncaught exception in the GL thread crashes the process.
        }
    }

    private fun initGL() {
        GLES20.glClearColor(0.043f, 0.047f, 0.063f, 1.0f)  // #0B0C10

        programHandle = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle   = GLES20.glGetAttribLocation(programHandle, "a_Position")
        radarUniform     = GLES20.glGetUniformLocation(programHandle, "u_Radar")
        paletteUniform   = GLES20.glGetUniformLocation(programHandle, "u_Palette")
        centerUniform    = GLES20.glGetUniformLocation(programHandle, "u_Center")
        resolutionUniform = GLES20.glGetUniformLocation(programHandle, "u_Resolution")

        // Create and initialise radar texture (all zeros = no signal = black)
        val texHandles = IntArray(2)
        GLES20.glGenTextures(2, texHandles, 0)
        radarTexture   = texHandles[0]
        paletteTexture = texHandles[1]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val emptyRadar = ByteBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            TEXTURE_SIZE, TEXTURE_SIZE, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, emptyRadar
        )

        // Palette texture — will be initialised in first onDrawFrame
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paletteTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // VBO for full-screen quad
        val vboHandles = IntArray(1)
        GLES20.glGenBuffers(1, vboHandles, 0)
        quadVbo = vboHandles[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            quadVertices.capacity() * Float.SIZE_BYTES,
            quadVertices,
            GLES20.GL_STATIC_DRAW
        )
        glInitialized = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (!glInitialized) return
        GLES20.glViewport(0, 0, width, height)
        viewportWidth  = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!glInitialized) return

        // Upload palette if changed
        if (paletteDirty.compareAndSet(true, false)) {
            uploadPalette(activePalette)
        }

        // Upload radar texture if new spoke data arrived
        if (textureDirty.compareAndSet(true, false)) {
            val snapshot = synchronized(textureLock) { textureBuffer.copyOf() }
            val buf = ByteBuffer.wrap(snapshot)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                TEXTURE_SIZE, TEXTURE_SIZE,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf
            )
        }

        // Draw quad
        GLES20.glUseProgram(programHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
        GLES20.glUniform1i(radarUniform, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paletteTexture)
        GLES20.glUniform1i(paletteUniform, 1)

        GLES20.glUniform2f(centerUniform, centerX, centerY)
        GLES20.glUniform2f(resolutionUniform, viewportWidth.toFloat(), viewportHeight.toFloat())

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    // =======================================================================
    // Palette LUT helpers
    // =======================================================================

    /** Upload a 256×1 RGBA palette texture for the given [ColorPalette]. */
    private fun uploadPalette(palette: ColorPalette) {
        val lut = buildPaletteLut(palette)
        val buf = ByteBuffer.wrap(lut)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paletteTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            256, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
    }

    /**
     * Build a 256×4-byte (RGBA) palette lookup table.
     * Each byte quartet maps intensity [0..255] to an RGBA colour.
     */
    fun buildPaletteLut(palette: ColorPalette): ByteArray {
        val lut = ByteArray(256 * 4)
        for (i in 0..255) {
            val (r, g, b) = when (palette) {
                ColorPalette.GREEN -> Triple(0, i, 0)
                ColorPalette.YELLOW -> Triple(i, i, 0)
                ColorPalette.NIGHT_RED -> Triple(i, 0, 0)
                ColorPalette.MULTI_COLOR -> multiColorRgb(i)
            }
            lut[i * 4 + 0] = r.toByte()
            lut[i * 4 + 1] = g.toByte()
            lut[i * 4 + 2] = b.toByte()
            lut[i * 4 + 3] = 255.toByte()  // always fully opaque
        }
        return lut
    }

    /**
     * Maps intensity 0–255 to an RGB triple for the MULTI_COLOR palette.
     * Bands: black → blue → cyan → green → yellow → red → white
     */
    private fun multiColorRgb(i: Int): Triple<Int, Int, Int> {
        return when {
            i < 51  -> Triple(0, 0, (i * 5))
            i < 102 -> Triple(0, ((i - 51) * 5), 255)
            i < 153 -> Triple(0, 255, (255 - (i - 102) * 5))
            i < 204 -> Triple(((i - 153) * 5), 255, 0)
            else    -> Triple(255, (255 - (i - 204) * 5), 0)
        }
    }

    // =======================================================================
    // Shader helpers
    // =======================================================================

    private fun compileShader(type: Int, source: String): Int {
        val handle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val status = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile error: ${GLES20.glGetShaderInfoLog(handle)}"
        }
        return handle
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Program link error: ${GLES20.glGetProgramInfoLog(prog)}"
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }
}
