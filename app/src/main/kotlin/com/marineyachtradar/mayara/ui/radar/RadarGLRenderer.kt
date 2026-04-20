package com.marineyachtradar.mayara.ui.radar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.RadarLegend
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
 * A [textureAngleSize]×[TEXTURE_RANGE_SIZE] [GL_LUMINANCE][GLES20.GL_LUMINANCE]
 * texture stores the radar sweep:
 *   - Column index  = spoke angle, mapped by `angle * textureAngleSize / spokesPerRevolution`
 *   - Row index 0   = radar center (nearest range)
 *   - Row index [TEXTURE_RANGE_SIZE]-1 = farthest range
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
        /** Default angular (column) resolution — used until configured by [configureForSpokes]. */
        const val DEFAULT_TEXTURE_ANGLE_SIZE = 2048
        /** Range (row) resolution — accommodates typical spoke data lengths. */
        const val TEXTURE_RANGE_SIZE = 1024
        @Deprecated("Use textureAngleSize or TEXTURE_RANGE_SIZE", level = DeprecationLevel.HIDDEN)
        const val TEXTURE_ANGLE_SIZE = DEFAULT_TEXTURE_ANGLE_SIZE   // back-compat for tests
        @Deprecated("Use textureAngleSize or TEXTURE_RANGE_SIZE", level = DeprecationLevel.HIDDEN)
        const val TEXTURE_SIZE = DEFAULT_TEXTURE_ANGLE_SIZE   // back-compat for tests
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 10f

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
uniform vec4 u_RingColor;    // range ring colour (palette-aware)
uniform float u_Scale;       // scale factor for portrait mode (≥1.0)
uniform float u_Zoom;        // visual pinch-zoom factor (≥1.0)

const float PI = 3.14159265;
const vec4 BACKGROUND = vec4(0.043, 0.047, 0.063, 1.0); // #0B0C10

// Range rings at 25 %, 50 %, 75 %, 100 % of maximum range.
const float R1 = 0.125;  // 25 % of radius 0.5
const float R2 = 0.250;  // 50 %
const float R3 = 0.375;  // 75 %
const float R4 = 0.500;  // 100 % (outer boundary ring)

void main() {
    // Convert fragment position to [-0.5 .. 0.5] centered coordinates.
    vec2 uv = gl_FragCoord.xy / u_Resolution - 0.5;
    // Correct for aspect ratio so the radar circle isn't stretched.
    float aspect = u_Resolution.x / u_Resolution.y;
    // In portrait, scale both axes up so the circle fills the width.
    // Zoom divides to magnify the view around the current center.
    vec2 radarPos = (vec2(uv.x * aspect, uv.y) * u_Scale - u_Center) / u_Zoom;

    float dist = length(radarPos);
    // Discard fragments outside the radar circle (radius = 0.5 in corrected space).
    if (dist > 0.5) {
        gl_FragColor = BACKGROUND;
        return;
    }

    // Range ring width: ~4 px scaled to normalized coordinates for visibility.
    float ringWidth = 4.0 / max(u_Resolution.x, u_Resolution.y);
    // Crosshair (N-S / E-W) line width: slightly thinner than rings.
    float crossWidth = 2.5 / max(u_Resolution.x, u_Resolution.y);

    // Draw N-S and E-W crosshair lines for quadrant separation.
    if ((abs(radarPos.x) < crossWidth || abs(radarPos.y) < crossWidth)) {
        gl_FragColor = u_RingColor;
        return;
    }

    // Draw range rings before the texture sample so rings appear on top.
    if (abs(dist - R1) < ringWidth ||
        abs(dist - R2) < ringWidth ||
        abs(dist - R3) < ringWidth ||
        abs(dist - R4) < ringWidth) {
        gl_FragColor = u_RingColor;
        return;
    }

    // v = 0 at screen centre (near range), 1 at screen edge (far range).
    float v = dist / 0.5;

    // atan(x, y): angle measured clockwise from North (y-up), result in [-PI..PI].
    float angle = atan(radarPos.x, radarPos.y);
    // Map angle to [0..1], starting at North.
    float u = fract(angle / (2.0 * PI));

    // Sample radar texture → legend index (stored in luminance channel).
    float intensity = texture2D(u_Radar, vec2(u, v)).r;

    // Look up colour from the palette LUT.
    vec4 color = texture2D(u_Palette, vec2(intensity, 0.5));

    // Blend: if legend alpha is ~0 (index 0 = no return), show background.
    gl_FragColor = mix(BACKGROUND, color, color.a);
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
    private var ringColorUniform = 0
    private var scaleUniform = 0
    private var zoomUniform = 0
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

    /** Current angular (column) resolution — may change via [configureForSpokes]. */
    @Volatile
    var textureAngleSize = DEFAULT_TEXTURE_ANGLE_SIZE
        private set

    /** Flat textureAngleSize×TEXTURE_RANGE_SIZE luminance buffer: [col + row * textureAngleSize] = intensity. */
    private var textureBuffer = ByteArray(textureAngleSize * TEXTURE_RANGE_SIZE)

    private val textureDirty = AtomicBoolean(false)
    private val textureSizeChanged = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // Pan state
    // -----------------------------------------------------------------------

    @Volatile
    internal var centerX = 0f

    @Volatile
    internal var centerY = 0f

    @Volatile
    internal var zoomLevel = 1f

    // -----------------------------------------------------------------------
    // Palette state
    // -----------------------------------------------------------------------

    @Volatile
    private var activePalette = ColorPalette.GREEN

    /** Server-provided legend LUT bytes (256×4 RGBA); null = use local palette. */
    @Volatile
    private var legendLut: ByteArray? = null

    private val paletteDirty = AtomicBoolean(true) // upload on first frame

    private val resolutionDirty = AtomicBoolean(true) // upload on first frame and orientation change

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
        return ((angle.toLong() * textureAngleSize) / spokesPerRevolution)
            .toInt()
            .coerceIn(0, textureAngleSize - 1)
    }

    /**
     * Write spoke intensity bytes into a column of [textureBuffer].
     * Must be called while holding [textureLock].
     */
    fun writeColumn(col: Int, data: ByteArray) {
        val angleSize = textureAngleSize
        val rows = minOf(data.size, TEXTURE_RANGE_SIZE)
        for (row in 0 until rows) {
            textureBuffer[col + row * angleSize] = data[row]
        }
        // Zero-pad remaining rows if spoke is shorter than texture height.
        for (row in rows until TEXTURE_RANGE_SIZE) {
            textureBuffer[col + row * angleSize] = 0
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

    /** Multiply the current zoom by [scaleFactor], clamping to [MIN_ZOOM]..[MAX_ZOOM]. */
    fun applyZoom(scaleFactor: Float) {
        zoomLevel = (zoomLevel * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Reset zoom to 1× (called on range change). */
    fun resetZoom() {
        zoomLevel = 1f
    }

    /** Switch the active colour palette. */
    fun setPalette(palette: ColorPalette) {
        if (activePalette != palette) {
            activePalette = palette
            paletteDirty.set(true)
        }
    }

    /**
     * Set the palette from the server-provided legend colour table.
     * When set, the legend LUT takes precedence over the local [ColorPalette].
     */
    fun setLegendPalette(legend: RadarLegend?) {
        legendLut = legend?.let { buildLegendLut(it) }
        paletteDirty.set(true)
    }

    /** Clear the entire radar texture buffer (e.g. when transmission stops). */
    fun clearAll() {
        synchronized(textureLock) {
            textureBuffer.fill(0)
        }
        textureDirty.set(true)
    }

    /**
     * Configure the angular texture resolution to match the radar's spoke count.
     * Computes the next power of 2 ≥ [spokesPerRevolution] (minimum 512).
     * Safe to call from any thread; the GL texture is recreated on the next frame.
     */
    fun configureForSpokes(spokesPerRevolution: Int) {
        val newSize = nextPowerOf2(maxOf(spokesPerRevolution, 512))
        if (newSize != textureAngleSize) {
            synchronized(textureLock) {
                textureAngleSize = newSize
                textureBuffer = ByteArray(newSize * TEXTURE_RANGE_SIZE)
            }
            textureSizeChanged.set(true)
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
        ringColorUniform = GLES20.glGetUniformLocation(programHandle, "u_RingColor")
        scaleUniform     = GLES20.glGetUniformLocation(programHandle, "u_Scale")
        zoomUniform      = GLES20.glGetUniformLocation(programHandle, "u_Zoom")

        // Create and initialise radar texture (all zeros = no signal = black)
        val texHandles = IntArray(2)
        GLES20.glGenTextures(2, texHandles, 0)
        radarTexture   = texHandles[0]
        paletteTexture = texHandles[1]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val angleSize = textureAngleSize
        val emptyRadar = ByteBuffer.allocate(angleSize * TEXTURE_RANGE_SIZE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            angleSize, TEXTURE_RANGE_SIZE, 0,
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
        resolutionDirty.set(true)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!glInitialized) return

        // Upload palette if changed
        if (paletteDirty.compareAndSet(true, false)) {
            val legend = legendLut
            if (legend != null) {
                uploadPaletteBytes(legend)
            } else {
                uploadPalette(activePalette)
            }
        }

        // Recreate radar texture if size changed (e.g. new radar with different spokesPerRevolution)
        if (textureSizeChanged.compareAndSet(true, false)) {
            recreateRadarTexture()
            textureDirty.set(true)
        }

        // Upload radar texture if new spoke data arrived
        if (textureDirty.compareAndSet(true, false)) {
            val (snapshot, angleSize) = synchronized(textureLock) {
                textureBuffer.copyOf() to textureAngleSize
            }
            val buf = ByteBuffer.wrap(snapshot)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                angleSize, TEXTURE_RANGE_SIZE,
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

        if (resolutionDirty.getAndSet(false)) {
            GLES20.glUniform2f(resolutionUniform, viewportWidth.toFloat(), viewportHeight.toFloat())
        }

        // In portrait, scale up so the full circle is visible.
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val scale = if (aspect < 1f) 1f / aspect else 1f
        GLES20.glUniform1f(scaleUniform, scale)

        // Upload zoom factor
        GLES20.glUniform1f(zoomUniform, zoomLevel)

        // Upload palette-aware ring color
        val rc = ringColorForPalette(activePalette)
        GLES20.glUniform4f(ringColorUniform, rc[0], rc[1], rc[2], rc[3])

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
        uploadPaletteBytes(lut)
    }

    /** Upload a raw 256×4-byte RGBA LUT as the palette texture. */
    private fun uploadPaletteBytes(lut: ByteArray) {
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
     * Index 0 is transparent (no radar return).
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
            lut[i * 4 + 3] = if (i == 0) 0.toByte() else 255.toByte()
        }
        return lut
    }

    /**
     * Build a 256×4-byte (RGBA) palette from the server legend.
     * Parses `#rrggbbaa` hex colour strings from [RadarLegend.pixels].
     */
    fun buildLegendLut(legend: RadarLegend): ByteArray {
        val lut = ByteArray(256 * 4)
        for (i in 0..255) {
            if (i < legend.pixels.size) {
                val hex = legend.pixels[i].color
                val rgba = parseHexColor(hex)
                lut[i * 4 + 0] = rgba[0]
                lut[i * 4 + 1] = rgba[1]
                lut[i * 4 + 2] = rgba[2]
                lut[i * 4 + 3] = rgba[3]
            }
            // else: stays at 0,0,0,0 (transparent)
        }
        return lut
    }

    /**
     * Parse a CSS-style hex colour `#rrggbbaa` or `#rrggbb` into a 4-byte RGBA array.
     */
    private fun parseHexColor(hex: String): ByteArray {
        val h = hex.removePrefix("#")
        return try {
            when (h.length) {
                8 -> byteArrayOf(
                    h.substring(0, 2).toInt(16).toByte(),
                    h.substring(2, 4).toInt(16).toByte(),
                    h.substring(4, 6).toInt(16).toByte(),
                    h.substring(6, 8).toInt(16).toByte(),
                )
                6 -> byteArrayOf(
                    h.substring(0, 2).toInt(16).toByte(),
                    h.substring(2, 4).toInt(16).toByte(),
                    h.substring(4, 6).toInt(16).toByte(),
                    0xFF.toByte(),
                )
                else -> byteArrayOf(0, 0, 0, 0)
            }
        } catch (_: NumberFormatException) {
            byteArrayOf(0, 0, 0, 0)
        }
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
    // Range ring colour per palette
    // =======================================================================

    /** Returns [r, g, b, a] in 0..1 range for the given palette. */
    private fun ringColorForPalette(palette: ColorPalette): FloatArray = when (palette) {
        ColorPalette.GREEN       -> floatArrayOf(0.0f, 0.20f, 0.0f, 0.7f)
        ColorPalette.YELLOW      -> floatArrayOf(0.20f, 0.18f, 0.0f, 0.7f)
        ColorPalette.NIGHT_RED   -> floatArrayOf(0.20f, 0.0f, 0.0f, 0.7f)
        ColorPalette.MULTI_COLOR -> floatArrayOf(0.25f, 0.25f, 0.25f, 0.7f)
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

    // =======================================================================
    // Dynamic texture sizing helpers
    // =======================================================================

    /** Recreate the GL radar texture with the current [textureAngleSize]. GL thread only. */
    private fun recreateRadarTexture() {
        val angleSize = textureAngleSize
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, radarTexture)
        val empty = ByteBuffer.allocate(angleSize * TEXTURE_RANGE_SIZE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            angleSize, TEXTURE_RANGE_SIZE, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, empty
        )
    }

    /** Return the smallest power of 2 ≥ [n]. */
    private fun nextPowerOf2(n: Int): Int {
        if (n <= 0) return 1
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }
}
