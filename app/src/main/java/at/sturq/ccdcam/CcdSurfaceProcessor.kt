package at.sturq.ccdcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import at.sturq.ccdcam.camera.FrameDecimator
import at.sturq.ccdcam.camera.FrameRateMeter
import at.sturq.ccdcam.geometry.EffectGeometry
import at.sturq.ccdcam.osd.OsdGlRenderer
import at.sturq.ccdcam.osd.OsdQuad
import at.sturq.ccdcam.profile.CameraProfile
import at.sturq.ccdcam.profile.Profiles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Runs ccd.frag/passthrough.vert for the UseCase(s) bound to its CameraEffect.
 *
 * One EGL context, one SurfaceTexture for the camera input, one EGLSurface per output. The app
 * uses two instances — one for PREVIEW, one for VIDEO_CAPTURE — so each gets its own camera
 * stream and the burned-in OSD ([burnsOsd]) reaches the recording only.
 */
class CcdSurfaceProcessor(
    context: Context,
    name: String = "CcdProcessor",
    /** Draws [osdQuads] over the picture (video instance only). */
    private val burnsOsd: Boolean = false,
    /** Output rate converter (video instance: PAL 25 fps CFR from the native camera rate). */
    private val decimator: FrameDecimator? = null,
) : SurfaceProcessor {

    private val vertexSrc = context.assets.open("shaders/passthrough.vert")
        .bufferedReader().use { it.readText() }
    private val fragmentSrc = context.assets.open("shaders/ccd.frag")
        .bufferedReader().use { it.readText() }

    private val thread = HandlerThread(name).apply { start() }
    private val osd: OsdGlRenderer? = if (burnsOsd) OsdGlRenderer(context) else null
    private val interlacer = Interlacer(context)

    /** CW rotation already contained in the current output buffer (sensor -> buffer). */
    @Volatile var outputRotationCw: Int = 0
        private set

    /** Delivered frame rate of this stream, from camera frame timestamps. */
    val frameRateMeter = FrameRateMeter()

    /** Burned-in OSD quads (LCD units), or null for none. Replaced, never mutated. */
    @Volatile var osdQuads: List<OsdQuad>? = null

    /** A still: the next output frame read back. */
    private class StillRequest(val done: (StillFrame?) -> Unit)

    /**
     * RGBA pixels of one output frame, bottom row first (as glReadPixels returns them), and the
     * picture's rectangle in them (GL pixel coordinates, origin bottom-left).
     */
    class StillFrame(
        val width: Int, val height: Int, val rgba: java.nio.ByteBuffer,
        val left: Float, val bottom: Float, val right: Float, val top: Float,
    )

    private val stillRequest = java.util.concurrent.atomic.AtomicReference<StillRequest?>(null)

    /**
     * Photo from this stream (the preview instance, which always runs: the picture exactly as it
     * is shown, cropped to the picture rectangle by the caller). [done] runs on the GL thread
     * with the pixels, or null if no frame comes.
     */
    fun requestStill(done: (StillFrame?) -> Unit) {
        stillRequest.getAndSet(StillRequest(done))?.done?.invoke(null)
        handler.postDelayed({
            // no frame within 1.5 s (camera off / no output): give up
            stillRequest.getAndSet(null)?.done?.invoke(null)
        }, 1_500)
    }

    /** Width in LCD units the OSD quads were laid out for (1024 = 16:9, 768 = 4:3). */
    @Volatile var osdLcdWidth: Float = at.sturq.ccdcam.osd.OsdScene.LCD_W
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { r -> handler.post(r) }

    /** Look parameters; read once per camera frame so every output gets the same values. */
    @Volatile var profile: CameraProfile = Profiles.HC90E_PAL

    /**
     * Inputs for the upright picture geometry (geometry/EffectGeometry.kt).
     *
     * @param regionAspect width/height, buffer orientation, of the centred region of the output
     *   buffer the picture lives in; null = the whole buffer (each output is its own stream)
     * @param pictureAspect upright width/height of the emulated picture (4/3 or 16/9)
     * @param sensorRotationDegrees CameraInfo.getSensorRotationDegrees(targetRotation): CW
     *   rotation CameraX applies (pixels and/or MP4 metadata) to make the sensor image upright
     * @param lcdAspect upright aspect of the emulated LCD band the picture is fitted into
     *   (16:9 for HC90E), or null for the whole region
     */
    data class Framing(
        val regionAspect: Float?,
        val pictureAspect: Float,
        val sensorRotationDegrees: Int,
        val lcdAspect: Float? = null,
    )

    @Volatile var framing: Framing = Framing(null, 16f / 9f, 0)

    /** NightShot look (body switch). Visual only. */
    @Volatile var nightShot: Boolean = false

    /** Super NightShot (menu SUPER NS), effective only while [nightShot] is on. */
    @Volatile var superNightShot: Boolean = false

    /** FADER amount 0..1 and colour (true = white). */
    @Volatile var fade: Float = 0f
    @Volatile var fadeWhite: Boolean = false

    // EGL state — initialized lazily on processor thread
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private var aPosLoc = -1
    private var aTexLoc = -1
    private var uTexMatrixLoc = -1
    private var uResLoc = -1
    private var uTimeLoc = -1
    private var uNightShotLoc = -1
    private var uFadeLoc = -1
    private var uFadeWhiteLoc = -1
    private var uTextureLoc = -1
    private lateinit var lookUniforms: LookUniforms
    private var uBufferToEffectLoc = -1
    private var uEffectToBufferLoc = -1
    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer
    private val texMatrix = FloatArray(16)

    private var surfaceTexture: SurfaceTexture? = null
    private var inputResolutionW = 0
    private var inputResolutionH = 0

    private val outputs = mutableMapOf<SurfaceOutput, OutputEntry>()
    private val startNs = System.nanoTime()
    private var initialized = false
    private var released = false

    private class OutputEntry(
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        /** CW rotation from sensor to this buffer, from SurfaceOutput.getSensorToBufferTransform. */
        val sensorToBufferCw: Int,
        /** Buffer is a mirror image (front-camera preview). */
        val mirrored: Boolean = false,
    ) {
        var cachedFor: Pair<Framing, Int>? = null
        var bufferToEffect: FloatArray? = null
        var effectToBuffer: FloatArray? = null
        var lcdToBuffer: FloatArray? = null
        /** Offscreen pair for the interlace weave (created on first interlaced frame). */
        var weave: Interlacer.Target? = null
        var frameCount = 0L
    }

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (released) { request.willNotProvideSurface(); return@post }
            ensureInitialized()
            frameRateMeter.reset()
            decimator?.reset()
            inputResolutionW = request.resolution.width
            inputResolutionH = request.resolution.height
            // recreate SurfaceTexture for each new input
            surfaceTexture?.release()
            val st = SurfaceTexture(textureId)
            st.setDefaultBufferSize(inputResolutionW, inputResolutionH)
            st.setOnFrameAvailableListener({ handler.post { renderFrame() } }, handler)
            surfaceTexture = st
            val surface = Surface(st)
            request.provideSurface(surface, executor) {
                handler.post {
                    surface.release()
                    if (surfaceTexture === st) {
                        st.release()
                        surfaceTexture = null
                    }
                }
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        handler.post {
            if (released) { output.close(); return@post }
            ensureInitialized()
            val outSurface = output.getSurface(executor) { event ->
                handler.post {
                    outputs.remove(output)?.let { entry ->
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                        interlacer.destroy(entry.weave)
                        try { EGL14.eglDestroySurface(eglDisplay, entry.eglSurface) } catch (_: Throwable) {}
                    }
                    output.close()
                }
            }
            // One output per target: on a rebind (e.g. camera flip) CameraX hands out the same
            // PreviewView Surface again before the old output's close event arrives. Release the
            // old EGL surface first, or EGL refuses a second window surface on that Surface.
            val stale = outputs.keys.filter { it.targets and output.targets != 0 }
            if (stale.isNotEmpty()) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                for (old in stale) {
                    outputs.remove(old)?.let { e ->
                        interlacer.destroy(e.weave)
                        try { EGL14.eglDestroySurface(eglDisplay, e.eglSurface) } catch (_: Throwable) {}
                    }
                    old.close()
                }
            }
            val attribs = intArrayOf(EGL14.EGL_NONE)
            var eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outSurface, attribs, 0)
            if (eglSurf == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreateWindowSurface failed (0x${Integer.toHexString(EGL14.eglGetError())}), retrying once")
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outSurface, attribs, 0)
            }
            if (eglSurf == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed for output ${output.size} (0x${Integer.toHexString(EGL14.eglGetError())})")
                output.close()
                return@post
            }
            val m = FloatArray(9)
            output.sensorToBufferTransform.getValues(m)
            val sensorToBufferCw = EffectGeometry.rotationCwDegreesOf(m)
            Log.i(TAG, "output size=${output.size} targets=${output.targets} " +
                "sensorToBuffer=${m.contentToString()} rotationCw=$sensorToBufferCw")
            outputRotationCw = sensorToBufferCw
            outputs[output] = OutputEntry(
                eglSurf, output.size.width, output.size.height, sensorToBufferCw,
                EffectGeometry.isMirrored(m),
            )
        }
    }

    private fun renderFrame() {
        val st = surfaceTexture ?: return
        if (outputs.isEmpty()) return
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (e: Throwable) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        // Video: keep only the frames on the 25 fps grid, stamped exactly on it (CFR).
        // A tick the camera missed is filled with this frame (no holes in the 25p file).
        val ticks = decimator?.ticks(st.timestamp) ?: longArrayOf(st.timestamp)
        val frameProfile = profile
        val frameFraming = framing
        for (pts in ticks) {
            frameRateMeter.onFrame(pts)
            for ((output, entry) in outputs) {
                if (!EGL14.eglMakeCurrent(eglDisplay, entry.eglSurface, entry.eglSurface, eglContext)) continue
                drawOnce(entry, output, frameProfile, frameFraming)
                stillRequest.getAndSet(null)?.let { readStill(entry, it) }
                EGLExt.eglPresentationTimeANDROID(eglDisplay, entry.eglSurface, pts)
                EGL14.eglSwapBuffers(eglDisplay, entry.eglSurface)
            }
        }
    }

    private val combined = FloatArray(16)

    /** Reads the back buffer just drawn (before the swap) and where the picture lies in it. */
    private fun readStill(entry: OutputEntry, req: StillRequest) {
        try {
            val m = entry.effectToBuffer ?: return req.done(null)
            // picture uv corners -> buffer uv (GL mat3, column-major: x' = m0 x + m3 y + m6)
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            for ((i, c) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).withIndex()) {
                xs[i] = (m[0] * c.first + m[3] * c.second + m[6]) * entry.width
                ys[i] = (m[1] * c.first + m[4] * c.second + m[7]) * entry.height
            }
            val buf = java.nio.ByteBuffer.allocateDirect(entry.width * entry.height * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, entry.width, entry.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val w = entry.width.toFloat()
            val h = entry.height.toFloat()
            req.done(StillFrame(
                entry.width, entry.height, buf,
                xs.min().coerceIn(0f, w), ys.min().coerceIn(0f, h), xs.max().coerceIn(0f, w), ys.max().coerceIn(0f, h),
            ))
        } catch (t: Throwable) {
            Log.w(TAG, "still readback failed", t)
            req.done(null)
        }
    }

    private fun drawOnce(entry: OutputEntry, output: SurfaceOutput, look: CameraProfile, fr: Framing) {
        val w = entry.width
        val h = entry.height
        // updateTransformMatrix(updated, original): combines camera tex transform with the
        // per-output rotation/mirror CameraX wants applied, so each output gets its own correct
        // matrix without any custom rotation math on our side.
        output.updateTransformMatrix(combined, texMatrix)

        // Interlace: render the look offscreen, then weave it with the previous frame below.
        val weave = if (look.shader.interlace > 0f) {
            entry.weave ?: interlacer.create(w, h).also { entry.weave = it }
        } else null
        // Super NightShot slow shutter: new picture every SNS_HOLD frames, repeated between.
        val sns = nightShot && superNightShot && weave != null
        val n = entry.frameCount++
        if (sns && weave!!.hasPrev && Interlacer.isHeldFrame(n)) {
            updateGeometry(entry, fr)
            entry.bufferToEffect?.let {
                interlacer.present(weave, it, look.shader.lines, vertexBuf, texBuf, Interlacer.SNS_BLEND)
            }
            val q = osdQuads
            val l = entry.lcdToBuffer
            if (osd != null && q != null && l != null) osd.draw(q, l, osdLcdWidth)
            return
        }
        if (weave != null) interlacer.begin(weave)

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, combined, 0)
        GLES20.glUniform2f(uResLoc, w.toFloat(), h.toFloat())
        GLES20.glUniform1f(uTimeLoc, (System.nanoTime() - startNs) / 1e9f)
        GLES20.glUniform1f(uNightShotLoc, if (!nightShot) 0f else if (superNightShot) 2f else 1f)
        GLES20.glUniform1f(uFadeLoc, fade)
        GLES20.glUniform1f(uFadeWhiteLoc, if (fadeWhite) 1f else 0f)
        lookUniforms.apply(look)
        updateGeometry(entry, fr)
        GLES20.glUniformMatrix3fv(uBufferToEffectLoc, 1, false, entry.bufferToEffect, 0)
        GLES20.glUniformMatrix3fv(uEffectToBufferLoc, 1, false, entry.effectToBuffer, 0)

        vertexBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)

        val b2e = entry.bufferToEffect
        if (weave != null && b2e != null) {
            interlacer.finish(weave, b2e, look.shader.lines, vertexBuf, texBuf, if (sns) Interlacer.SNS_BLEND else 0f)
        }

        // OSD goes on top of the woven frame (no combing on the indicators).
        val quads = osdQuads
        val lcd = entry.lcdToBuffer
        if (osd != null && quads != null && lcd != null) osd.draw(quads, lcd, osdLcdWidth)
    }

    /** Recomputes picture-space matrices only when the framing or this output changes. */
    private fun updateGeometry(entry: OutputEntry, fr: Framing) {
        val uprightCw = EffectGeometry.uprightCwDegrees(
            fr.sensorRotationDegrees, entry.sensorToBufferCw, entry.mirrored,
        )
        val key = fr to uprightCw
        if (entry.cachedFor == key) return
        val region = fr.regionAspect ?: (entry.width.toFloat() / entry.height)
        val g = EffectGeometry.compute(
            entry.width, entry.height, region, uprightCw, fr.pictureAspect, fr.lcdAspect,
        )
        entry.bufferToEffect = g.bufferToEffect.toGlMat3()
        entry.effectToBuffer = g.effectToBuffer.toGlMat3()
        entry.lcdToBuffer = g.lcdToBuffer.toGlMat3()
        entry.cachedFor = key
        Log.i(TAG, "picture geometry: buffer=${entry.width}x${entry.height} $fr uprightCw=$uprightCw")
    }

    private fun ensureInitialized() {
        if (initialized) return
        initEgl()
        initGl()
        initialized = true
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfg = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val recordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_ANDROID_RECORDABLE, 1,
            EGL14.EGL_NONE,
        )
        val ok = EGL14.eglChooseConfig(eglDisplay, recordable, 0, cfg, 0, 1, n, 0) && n[0] > 0
        if (!ok) {
            val fallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            EGL14.eglChooseConfig(eglDisplay, fallback, 0, cfg, 0, 1, n, 0)
        }
        eglConfig = cfg[0] ?: error("no EGL config")
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun initGl() {
        program = GlUtil.makeProgram(vertexSrc, fragmentSrc)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uResLoc = GLES20.glGetUniformLocation(program, "uResolution")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uNightShotLoc = GLES20.glGetUniformLocation(program, "uNightShot")
        uFadeLoc = GLES20.glGetUniformLocation(program, "uFade")
        uFadeWhiteLoc = GLES20.glGetUniformLocation(program, "uFadeWhite")
        uTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")
        lookUniforms = LookUniforms(program)
        uBufferToEffectLoc = GLES20.glGetUniformLocation(program, "uBufferToEffect")
        uEffectToBufferLoc = GLES20.glGetUniformLocation(program, "uEffectToBuffer")

        val vertices = floatArrayOf(-1f, -1f,  1f, -1f,  -1f,  1f,  1f,  1f)
        val texCoords = floatArrayOf( 0f,  0f,  1f,  0f,   0f,  1f,  1f,  1f)
        vertexBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        texBuf = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun release() {
        handler.post {
            released = true
            for ((output, entry) in outputs) {
                try { EGL14.eglDestroySurface(eglDisplay, entry.eglSurface) } catch (_: Throwable) {}
                output.close()
            }
            outputs.clear()
            surfaceTexture?.release()
            surfaceTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE)
                    EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                // No eglTerminate: EGL_DEFAULT_DISPLAY is shared by both processor instances;
                // terminating it here could break the other one. The process owns the display.
            }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "CcdProcessor"
        private const val EGL_ANDROID_RECORDABLE = 0x3142
    }
}
