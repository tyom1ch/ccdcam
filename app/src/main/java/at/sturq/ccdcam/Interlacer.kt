package at.sturq.ccdcam

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * PAL interlace emulation by weaving two frames (shaders/weave.frag): each output renders its
 * look into an offscreen texture, then the screen/encoder surface gets even picture lines from
 * this frame and odd lines from the previous one. GL thread only, context current.
 */
class Interlacer(context: Context) {

    /** Two colour textures + framebuffers for one output, ping-ponged every frame. */
    class Target internal constructor(val width: Int, val height: Int) {
        internal val tex = IntArray(2)
        internal val fbo = IntArray(2)
        internal var cur = 0
        internal var hasPrev = false
    }

    private val vsrc = GlUtil.loadAsset(context, "shaders/weave.vert")
    private val fsrc = GlUtil.loadAsset(context, "shaders/weave.frag")
    private var program = 0
    private var aPos = -1
    private var aTex = -1
    private var uCur = -1
    private var uPrev = -1
    private var uB2E = -1
    private var uLines = -1

    private fun ensureProgram() {
        if (program != 0) return
        program = GlUtil.makeProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uCur = GLES20.glGetUniformLocation(program, "sCur")
        uPrev = GLES20.glGetUniformLocation(program, "sPrev")
        uB2E = GLES20.glGetUniformLocation(program, "uBufferToEffect")
        uLines = GLES20.glGetUniformLocation(program, "uLines")
    }

    fun create(width: Int, height: Int): Target {
        val t = Target(width, height)
        GLES20.glGenTextures(2, t.tex, 0)
        GLES20.glGenFramebuffers(2, t.fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, t.tex[i], 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "FBO incomplete 0x${Integer.toHexString(status)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return t
    }

    fun destroy(t: Target?) {
        if (t == null) return
        GLES20.glDeleteFramebuffers(2, t.fbo, 0)
        GLES20.glDeleteTextures(2, t.tex, 0)
    }

    /** Redirects the following draw into this frame's offscreen texture. */
    fun begin(t: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo[t.cur])
    }

    /** Draws the woven frame into the window surface (framebuffer 0) and swaps the pair. */
    fun finish(t: Target, bufferToEffect: FloatArray, lines: Float, quad: FloatBuffer, uv: FloatBuffer) {
        ensureProgram()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, t.width, t.height)
        GLES20.glUseProgram(program)
        val prev = if (t.hasPrev) 1 - t.cur else t.cur
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex[t.cur])
        GLES20.glUniform1i(uCur, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex[prev])
        GLES20.glUniform1i(uPrev, 3)
        GLES20.glUniformMatrix3fv(uB2E, 1, false, bufferToEffect, 0)
        GLES20.glUniform1f(uLines, lines)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        t.hasPrev = true
        t.cur = 1 - t.cur
    }

    companion object {
        private const val TAG = "Interlacer"

        /** Field of a picture line (y = upright picture uv, 0 bottom): 0 = this frame, 1 = previous. */
        fun fieldOf(pictureY: Float, lines: Float): Int = (Math.floor(pictureY * lines.toDouble()).toLong() % 2).toInt()
    }
}
