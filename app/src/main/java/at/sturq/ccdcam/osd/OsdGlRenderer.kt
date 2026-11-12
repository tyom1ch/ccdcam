package at.sturq.ccdcam.osd

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLUtils
import at.sturq.ccdcam.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Burns the OSD into a GL output: one draw call of atlas quads, alpha-blended over the picture.
 * The atlas texture is uploaded once (GL_NEAREST — pixels stay crisp); the vertex buffer is only
 * rebuilt when the quad list changes (≈1 Hz while recording), never per frame.
 * Must be used on the GL thread that owns the current context.
 */
class OsdGlRenderer(context: Context) {

    private val vsrc = GlUtil.loadAsset(context, "shaders/osd.vert")
    private val fsrc = GlUtil.loadAsset(context, "shaders/osd.frag")
    private val atlas: OsdAtlas = OsdResources.get(context).second

    private var program = 0
    private var texture = 0
    private var aPos = -1
    private var aUv = -1
    private var aColor = -1
    private var uLcdToBuffer = -1
    private var uAtlas = -1

    private var builtFor: List<OsdQuad>? = null
    private var builtW = 0f
    private var vertices: FloatBuffer? = null
    private var vertexCount = 0

    private fun ensureInit() {
        if (program != 0) return
        program = GlUtil.makeProgram(vsrc, fsrc)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uLcdToBuffer = GLES20.glGetUniformLocation(program, "uLcdToBuffer")
        uAtlas = GLES20.glGetUniformLocation(program, "sAtlas")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val bmp = OsdResources.bitmap(atlas)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    /** Rebuilds the vertex buffer only when [quads] changed. */
    private fun upload(quads: List<OsdQuad>, lcdW: Float) {
        if (builtFor === quads && builtW == lcdW) return
        val data = OsdVertices.build(quads, atlas.width, atlas.height, lcdW)
        val buf = vertices?.takeIf { it.capacity() >= data.size }
            ?: ByteBuffer.allocateDirect(maxOf(data.size, 64) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.clear()
        buf.put(data)
        buf.position(0)
        vertices = buf
        vertexCount = data.size / OsdVertices.FLOATS_PER_VERTEX
        builtFor = quads
        builtW = lcdW
    }

    /** Draws [quads] into the current surface; [lcdToBuffer] is a column-major mat3. */
    fun draw(quads: List<OsdQuad>, lcdToBuffer: FloatArray, lcdW: Float = OsdScene.LCD_W) {
        if (quads.isEmpty()) return
        ensureInit()
        upload(quads, lcdW)
        val buf = vertices ?: return
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uAtlas, 1)
        GLES20.glUniformMatrix3fv(uLcdToBuffer, 1, false, lcdToBuffer, 0)
        val stride = OsdVertices.FLOATS_PER_VERTEX * 4
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(2)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(4)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        program = 0
        texture = 0
    }
}

/** Interleaved vertex data for [OsdGlRenderer]: pos(2, LCD uv y-up) uv(2) rgba(4). Pure. */
object OsdVertices {
    const val FLOATS_PER_VERTEX = 8

    fun build(quads: List<OsdQuad>, atlasW: Int, atlasH: Int, lcdW: Float = OsdScene.LCD_W): FloatArray {
        val out = FloatArray(quads.size * 6 * FLOATS_PER_VERTEX)
        var i = 0
        fun v(x: Float, y: Float, u: Float, t: Float, c: Int) {
            out[i++] = x / lcdW
            out[i++] = 1f - y / OsdScene.LCD_H        // LCD units are y-down, GL uv is y-up
            out[i++] = u
            out[i++] = t
            out[i++] = ((c shr 16) and 0xFF) / 255f
            out[i++] = ((c shr 8) and 0xFF) / 255f
            out[i++] = (c and 0xFF) / 255f
            out[i++] = ((c ushr 24) and 0xFF) / 255f
        }
        for (q in quads) {
            val u0 = q.src.x.toFloat() / atlasW
            val v0 = q.src.y.toFloat() / atlasH          // atlas row 0 = top = texture t 0
            val u1 = (q.src.x + q.src.w).toFloat() / atlasW
            val v1 = (q.src.y + q.src.h).toFloat() / atlasH
            v(q.left, q.top, u0, v0, q.color); v(q.right, q.top, u1, v0, q.color); v(q.left, q.bottom, u0, v1, q.color)
            v(q.right, q.top, u1, v0, q.color); v(q.right, q.bottom, u1, v1, q.color); v(q.left, q.bottom, u0, v1, q.color)
        }
        return out
    }
}
