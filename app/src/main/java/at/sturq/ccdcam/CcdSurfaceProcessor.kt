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
import at.sturq.ccdcam.geometry.EffectGeometry
import at.sturq.ccdcam.profile.CameraProfile
import at.sturq.ccdcam.profile.Profiles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Runs ccd.frag/passthrough.vert across every UseCase bound to a CameraEffect.
 *
 * One shared EGL context, one SurfaceTexture for the camera input, N EGLSurfaces (one per
 * output: preview, video encoder). Each frame from the camera is drawn to every output with
 * the per-output transform supplied by [SurfaceOutput.updateTransformMatrix], so CameraX
 * handles rotation natively — we only do the look.
 */
class CcdSurfaceProcessor(context: Context) : SurfaceProcessor {

    private val vertexSrc = context.assets.open("shaders/passthrough.vert")
        .bufferedReader().use { it.readText() }
    private val fragmentSrc = context.assets.open("shaders/ccd.frag")
        .bufferedReader().use { it.readText() }

    private val thread = HandlerThread("CcdProcessor").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { r -> handler.post(r) }

    /** Look parameters; read once per camera frame so every output gets the same values. */
    @Volatile var profile: CameraProfile = Profiles.HC90E_PAL

    /**
     * Inputs for the upright picture geometry (geometry/EffectGeometry.kt).
     *
     * @param regionAspect width/height, buffer (sensor) orientation, of the centred region the
     *   downstream preview and video crops share
     * @param pictureAspect upright width/height of the emulated picture (4/3 or 16/9)
     * @param sensorRotationDegrees CameraInfo.getSensorRotationDegrees(targetRotation): CW
     *   rotation CameraX applies (pixels and/or MP4 metadata) to make the sensor image upright
     * @param lcdAspect upright aspect of the emulated LCD band the picture is fitted into
     *   (16:9 for HC90E), or null for the whole region
     */
    data class Framing(
        val regionAspect: Float,
        val pictureAspect: Float,
        val sensorRotationDegrees: Int,
        val lcdAspect: Float? = null,
    )

    @Volatile var framing: Framing = Framing(16f / 9f, 16f / 9f, 0)

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
    ) {
        var cachedFor: Pair<Framing, Int>? = null
        var bufferToEffect: FloatArray? = null
        var effectToBuffer: FloatArray? = null
    }

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (released) { request.willNotProvideSurface(); return@post }
            ensureInitialized()
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
                        try { EGL14.eglDestroySurface(eglDisplay, entry.eglSurface) } catch (_: Throwable) {}
                    }
                    output.close()
                }
            }
            val attribs = intArrayOf(EGL14.EGL_NONE)
            val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outSurface, attribs, 0)
            if (eglSurf == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed for output ${output.size}")
                output.close()
                return@post
            }
            val m = FloatArray(9)
            output.sensorToBufferTransform.getValues(m)
            val sensorToBufferCw = EffectGeometry.rotationCwDegreesOf(m)
            Log.i(TAG, "output size=${output.size} targets=${output.targets} " +
                "sensorToBuffer=${m.contentToString()} rotationCw=$sensorToBufferCw")
            outputs[output] = OutputEntry(
                eglSurf, output.size.width, output.size.height, sensorToBufferCw,
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
        val pts = st.timestamp
        val frameProfile = profile
        val frameFraming = framing
        for ((output, entry) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, entry.eglSurface, entry.eglSurface, eglContext)) continue
            drawOnce(entry, output, frameProfile, frameFraming)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, entry.eglSurface, pts)
            EGL14.eglSwapBuffers(eglDisplay, entry.eglSurface)
        }
    }

    private val combined = FloatArray(16)

    private fun drawOnce(entry: OutputEntry, output: SurfaceOutput, look: CameraProfile, fr: Framing) {
        val w = entry.width
        val h = entry.height
        // updateTransformMatrix(updated, original): combines camera tex transform with the
        // per-output rotation/mirror CameraX wants applied, so each output gets its own correct
        // matrix without any custom rotation math on our side.
        output.updateTransformMatrix(combined, texMatrix)

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
    }

    /** Recomputes picture-space matrices only when the framing or this output changes. */
    private fun updateGeometry(entry: OutputEntry, fr: Framing) {
        val uprightCw = ((fr.sensorRotationDegrees - entry.sensorToBufferCw) % 360 + 360) % 360
        val key = fr to uprightCw
        if (entry.cachedFor == key) return
        val g = EffectGeometry.compute(
            entry.width, entry.height, fr.regionAspect, uprightCw, fr.pictureAspect, fr.lcdAspect,
        )
        entry.bufferToEffect = g.bufferToEffect.toGlMat3()
        entry.effectToBuffer = g.effectToBuffer.toGlMat3()
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
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "CcdProcessor"
        private const val EGL_ANDROID_RECORDABLE = 0x3142
    }
}
