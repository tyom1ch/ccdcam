package at.sturq.ccdcam.firmware

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * PLAY/EDIT player. A TextureView (unlike VideoView's SurfaceView) can be turned, so the
 * picture follows the phone like the firmware display: held sideways, the clip plays sideways
 * and fills the same LCD region the OSD is drawn in. API mirrors the VideoView calls used.
 */
class PlaybackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var uri: Uri? = null
    private var prepared = false
    private var startWhenPrepared = false

    private var onPrepared: MediaPlayer.OnPreparedListener? = null
    private var onCompletion: MediaPlayer.OnCompletionListener? = null
    private var onError: MediaPlayer.OnErrorListener? = null

    /** Clip size as displayed (after its rotation metadata). */
    private var videoW = 16
    private var videoH = 9

    /** How far the phone is turned clockwise (0/90/180/270), as for the firmware display. */
    var deviceRotationCw = 0
        set(value) { if (field != value) { field = value; updateTransform() } }

    /** Aspect of the LCD region in this view (FirmwareOsdView.regionAspectInView). */
    var regionAspect = 9f / 16f
        set(value) { field = value; updateTransform() }

    init {
        surfaceTextureListener = this
    }

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener) { onPrepared = l }
    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener) { onCompletion = l }
    fun setOnErrorListener(l: MediaPlayer.OnErrorListener) { onError = l }

    fun setVideoURI(u: Uri) {
        uri = u
        open()
    }

    fun start() {
        val p = player
        if (p != null && prepared) p.start() else startWhenPrepared = true
    }

    fun pause() {
        startWhenPrepared = false
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
    }

    fun seekTo(ms: Int) {
        if (prepared) player?.seekTo(ms)
    }

    val currentPosition: Int
        get() = if (prepared) player?.currentPosition ?: 0 else 0

    fun stopPlayback() {
        uri = null
        releasePlayer()
    }

    private fun open() {
        releasePlayer()
        val u = uri ?: return
        val st = surfaceTexture ?: return            // opened again once the texture exists
        readSize(u)
        val s = Surface(st).also { surface = it }
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(context, u)
            p.setSurface(s)
            p.setOnPreparedListener { mp ->
                prepared = true
                onPrepared?.onPrepared(mp)
                if (startWhenPrepared) { startWhenPrepared = false; mp.start() }
            }
            p.setOnCompletionListener { mp -> onCompletion?.onCompletion(mp) }
            p.setOnErrorListener { mp, what, extra -> onError?.onError(mp, what, extra) ?: false }
            p.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "open failed", t)
            onError?.onError(p, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun readSize(u: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, u)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 != 0) { videoW = h; videoH = w } else { videoW = w; videoH = h }
        } catch (t: Throwable) {
            Log.w(TAG, "metadata failed", t)
        } finally {
            runCatching { r.release() }
        }
        updateTransform()
    }

    private fun releasePlayer() {
        prepared = false
        startWhenPrepared = false
        player?.let { runCatching { it.reset(); it.release() } }
        player = null
        surface?.release()
        surface = null
    }

    /** Fits the clip into the LCD region, turned against the phone so it stays upright. */
    private fun updateTransform() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val region = LcdLayout.displayedRegion(w, h, regionAspect)
        val fr = LcdLayout.RotatedFrame(region, deviceRotationCw)
        val a = videoW.toFloat() / videoH
        val (cw, ch) = if (fr.width / fr.height > a) fr.height * a to fr.height else fr.width to fr.width / a
        val m = Matrix()
        m.setScale(cw / w, ch / h, w / 2f, h / 2f)
        m.postRotate(fr.canvasAngle, w / 2f, h / 2f)
        m.postTranslate(region.centerX - w / 2f, region.centerY - h / 2f)
        setTransform(m)
        invalidate()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        updateTransform()
        if (uri != null && player == null) open()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateTransform()
    }

    private companion object {
        const val TAG = "PlaybackView"
    }
}
