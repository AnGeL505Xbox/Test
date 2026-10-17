package com.example.ultrahdrwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max

/** Experimental HDR10/PQ wallpaper. One UI may still composite a wallpaper in SDR. */
class HDRWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallpaperEngine()

    private inner class WallpaperEngine : Engine() {
        private var renderer: GainmapRenderer? = null
        private var visible = false
        private var surfaceReady = false

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // Request a 10-bit buffer; this does not force the launcher to display HDR.
            holder.setFormat(PixelFormat.RGBA_1010102)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            if (visible) render()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (visible && surfaceReady) render()
            if (!visible) releaseRenderer()
        }

        private fun render() {
            if (!visible || !surfaceReady || !surfaceHolder.surface.isValid) return
            releaseRenderer()
            val path = getFileStreamPath("wallpaper.jpg")
            if (!path.exists()) { report("No se ha seleccionado ninguna imagen."); return }
            try {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(path)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    if (!bitmap.hasGainmap()) {
                        report("La fotografía no contiene un gain map Ultra HDR.")
                        return
                    }
                    val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                        .getDisplay(Display.DEFAULT_DISPLAY)
                    // Some devices report 1.0 until an HDR layer is displayed. 2.5 is a test target.
                    val hdrRatio = if (display?.isHdrSdrRatioAvailable == true)
                        max(2.5f, display.hdrSdrRatio) else 2.5f
                    renderer = GainmapRenderer(surfaceHolder, bitmap, hdrRatio).also {
                        val eglResult = it.draw()
                        report("HDR10/PQ: $eglResult. " +
                            "Ganancia solicitada=${"%.2f".format(hdrRatio)}x; " +
                            "referencia SDR=203 nits. " +
                            "EGL aceptado NO confirma brillo HDR en One UI.")
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (t: Throwable) {
                Log.e("UltraHDRWallpaper", "HDR renderer", t)
                report("Error del motor HDR: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        private fun report(message: String) {
            getSharedPreferences("hdr_diag", Context.MODE_PRIVATE)
                .edit().putString("status", message).apply()
            Log.i("UltraHDRWallpaper", message)
        }

        private fun releaseRenderer() { renderer?.release(); renderer = null }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releaseRenderer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() { releaseRenderer(); super.onDestroy() }
    }
}

/** Requires an EGL BT.2020/PQ 10-bit window surface. No silent SDR fallback. */
private class GainmapRenderer(
    private val holder: SurfaceHolder,
    private val image: Bitmap,
    private val headroom: Float
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var baseTexture = 0
    private var gainTexture = 0
    private var program = 0

    companion object {
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        // Assumption for PQ transfer: Android commonly uses ~203 nit SDR diffuse white.
        // This is a test reference, NOT the device's measured SDR white luminance.
        private const val SDR_WHITE_NITS = 203.0f
        private val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
            }
        """.trimIndent()
        private val FRAGMENT = """
            precision highp float;
            varying vec2 vUV;
            uniform sampler2D uBase;
            uniform sampler2D uGain;
            uniform vec2 uCrop;
            uniform vec3 uRatioMin;
            uniform vec3 uRatioMax;
            uniform vec3 uGamma;
            uniform vec3 uEpsSdr;
            uniform vec3 uEpsHdr;
            uniform float uWeight;
            uniform float uSdrWhiteNits;
            uniform mat3 uTo2020;

            vec3 srgbToLinear(vec3 c) {
                vec3 low = c / 12.92;
                vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
                return mix(low, high, step(vec3(0.04045), c));
            }
            void main() {
                vec2 uv = (vUV - vec2(0.5)) * uCrop + vec2(0.5);
                vec3 base = srgbToLinear(texture2D(uBase, uv).rgb);
                vec3 gm = texture2D(uGain, uv).rgb;
                vec3 logGain = mix(log(uRatioMin), log(uRatioMax),
                    pow(gm, uGamma));
                vec3 reconstructed = max(vec3(0.0),
                    (base + uEpsSdr) * exp(logGain * uWeight) - uEpsHdr);
                // Base primaries -> linear BT.2020; encode *absolute* PQ ST 2084.
                vec3 bt2020 = max(vec3(0.0), uTo2020 * reconstructed);
                vec3 n = clamp(bt2020 * (uSdrWhiteNits / 10000.0), 0.0, 1.0);
                vec3 p = pow(n, vec3(0.1593017578125));
                vec3 pq = pow((vec3(0.8359375) + 18.8515625 * p) /
                              (vec3(1.0) + 18.6875 * p), vec3(78.84375));
                gl_FragColor = vec4(pq, 1.0);
            }
        """.trimIndent()
    }

    fun draw(): String {
        try {
            val eglSummary = setupEgl()
            program = linkProgram(VERTEX, FRAGMENT)
            GLES20.glUseProgram(program)
            baseTexture = loadTexture(image)
            gainTexture = loadGainTexture(image.gainmap!!.gainmapContents)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTexture)
            GLES20.glUniform1i(uniform("uBase"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTexture)
            GLES20.glUniform1i(uniform("uGain"), 1)
            val gainmap = image.gainmap!!
            set3("uRatioMin", gainmap.ratioMin.map { max(it, 0.00001f) }.toFloatArray())
            set3("uRatioMax", gainmap.ratioMax.map { max(it, 0.00001f) }.toFloatArray())
            set3("uGamma", gainmap.gamma)
            set3("uEpsSdr", gainmap.epsilonSdr)
            set3("uEpsHdr", gainmap.epsilonHdr)
            val lower = max(1.0f, gainmap.minDisplayRatioForHdrTransition)
            val upper = max(lower + 0.001f, gainmap.displayRatioForFullHdr)
            val w = (ln(max(headroom, lower).toDouble() / lower) /
                ln(upper.toDouble() / lower)).toFloat().coerceIn(0f, 1f)
            GLES20.glUniform1f(uniform("uWeight"), w)
            GLES20.glUniform1f(uniform("uSdrWhiteNits"), SDR_WHITE_NITS)
            // Gainmap math is defined in the primaries of the base bitmap.
            // These matrices are column-major (OpenGL); RGB values remain linear.
            val srcColorSpace = image.colorSpace
            val isP3 = srcColorSpace == ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            val isSrgb = srcColorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
            check(isP3 || isSrgb) {
                "Espacio de color no admitido para reconstruccion: ${srcColorSpace?.name}"
            }
            val to2020 = if (isP3) floatArrayOf(
                0.753833f, 0.045744f, -0.001210f,
                0.198597f, 0.941777f, 0.017602f,
                0.047570f, 0.012479f, 0.983609f
            ) else floatArrayOf(
                0.627404f, 0.069097f, 0.016391f,
                0.329283f, 0.919540f, 0.088013f,
                0.043313f, 0.011362f, 0.895595f
            )
            GLES20.glUniformMatrix3fv(uniform("uTo2020"), 1, false, to2020, 0)
            val screenW = max(1, holder.surfaceFrame.width())
            val screenH = max(1, holder.surfaceFrame.height())
            val imageAspect = image.width.toFloat() / image.height
            val screenAspect = screenW.toFloat() / screenH
            val cropX = if (imageAspect > screenAspect) screenAspect / imageAspect else 1f
            val cropY = if (imageAspect < screenAspect) imageAspect / screenAspect else 1f
            GLES20.glUniform2f(uniform("uCrop"), cropX, cropY)
            GLES20.glViewport(0, 0, screenW, screenH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val coords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(coords)
            vertexBuffer.position(0)
            val position = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "OpenGL error" }
            check(EGL14.eglSwapBuffers(display, surface)) { "eglSwapBuffers fallo" }
            return "$eglSummary, color=${image.colorSpace?.name ?: "desconocido"}, frame enviado"
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    private fun setupEgl(): String {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL sin display" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "eglInitialize fallo" }
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        check(extensions.split(' ').contains("EGL_EXT_gl_colorspace_bt2020_pq")) {
            "Sin soporte EGL_EXT_gl_colorspace_bt2020_pq" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val counts = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_NONE
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, counts, 0) &&
              counts[0] > 0 && configs[0] != null) { "No hay EGLConfig RGB10_A2" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Sin contexto OpenGL ES 2" }
        surface = EGL14.eglCreateWindowSurface(display, config, holder,
            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT,
                EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) {
            "No se pudo crear superficie EGL BT.2020/PQ (error ${EGL14.eglGetError()})" }
        val colorspace = IntArray(1)
        check(EGL14.eglQuerySurface(display, surface, EGL_GL_COLORSPACE_KHR, colorspace, 0)) {
            "No se pudo consultar EGL colorspace" }
        check(colorspace[0] == EGL_GL_COLORSPACE_BT2020_PQ_EXT) {
            "EGL no confirmo BT.2020/PQ (valor=${colorspace[0]})" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent fallo" }
        val red = IntArray(1)
        val green = IntArray(1)
        val blue = IntArray(1)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_RED_SIZE, red, 0)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_GREEN_SIZE, green, 0)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_BLUE_SIZE, blue, 0)
        check(red[0] >= 10 && green[0] >= 10 && blue[0] >= 10) {
            "EGL no asigno configuracion de 10 bits" }
        return "EGL BT.2020/PQ verificado, RGB=${red[0]}/${green[0]}/${blue[0]} bits"
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun linkProgram(v: String, f: String): Int {
        val vtx = compile(GLES20.GL_VERTEX_SHADER, v)
        val frag = compile(GLES20.GL_FRAGMENT_SHADER, f)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vtx)
        GLES20.glAttachShader(p, frag)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vtx)
        GLES20.glDeleteShader(frag)
        check(ok[0] != 0) { "Link shader: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun uniform(name: String) = GLES20.glGetUniformLocation(program, name)
    private fun set3(name: String, data: FloatArray) {
        GLES20.glUniform3fv(uniform(name), 1, data, 0)
    }

    private fun loadTexture(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val id = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return id
    }

    private fun loadGainTexture(bitmap: Bitmap): Int {
        if (bitmap.config != Bitmap.Config.ALPHA_8) return loadTexture(bitmap)
        // ALPHA_8 gainmaps hold their gain in A, while the shader expects RGB.
        val data = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(data, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in data.indices) {
            val v = (data[i] ushr 24) and 255
            data[i] = Color.rgb(v, v, v)
        }
        val rgb = Bitmap.createBitmap(data, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        return try { loadTexture(rgb) } finally { rgb.recycle() }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            if (baseTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(baseTexture), 0)
            if (gainTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(gainTexture), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        program = 0
        baseTexture = 0
        gainTexture = 0
    }
}
