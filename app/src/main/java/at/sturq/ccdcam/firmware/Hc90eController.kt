package at.sturq.ccdcam.firmware

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.R
import at.sturq.ccdcam.databinding.ActivityMainBinding
import at.sturq.ccdcam.settings.SettingsStore
import java.util.concurrent.Executors

/**
 * Glue between MainActivity and the emulated HC90E firmware: shows/hides the firmware
 * display instead of the CCDCam HUD, keeps the virtual cassette, plays control sounds and runs
 * PLAY/EDIT tape playback. Nothing here touches CameraX or GL directly — camera on/off and
 * recording stop go through [Host].
 */
class Hc90eController(
    private val context: Context,
    private val b: ActivityMainBinding,
    private val store: SettingsStore,
    private val host: Host,
) : FirmwareOsdView.Listener {

    interface Host {
        fun isRecording(): Boolean
        fun requestStopRecording()
        /** Rebind the camera (leaving PLAY/EDIT). */
        fun cameraOn()
        /** Unbind the camera (PLAY/EDIT: the camcorder's camera section is off). */
        fun cameraOff()
        /** Camera mode for the shared shutter: true = still photo (MEMORY), false = movie (TAPE). */
        fun setPhotoMode(photo: Boolean)
    }

    val navigator = FirmwareNavigator(store.firmware, Lamp.TAPE)
    private val beeps = BeepPlayer()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "HC90eTape") }

    var active = false
        private set

    val lamp: Lamp get() = navigator.lamp

    /** True while the camera must stay unbound (PLAY/EDIT). */
    val cameraSuspended: Boolean get() = active && navigator.lamp == Lamp.PLAY_EDIT

    // ================= activation / lamps =================

    fun setActive(on: Boolean) {
        if (active == on) return
        if (!on && navigator.lamp == Lamp.PLAY_EDIT) leavePlayback()
        active = on
        val ccd = if (on) View.GONE else View.VISIBLE
        listOf(b.topHud, b.zoomBar, b.bottomHud, b.bracketTl, b.bracketTr, b.bracketBl, b.bracketBr)
            .forEach { it.visibility = ccd }
        b.firmwareOsd.visibility = if (on) View.VISIBLE else View.GONE
        b.modePlay.visibility = if (on) View.VISIBLE else View.GONE
        b.hcControls.visibility = if (on) View.VISIBLE else View.GONE
        b.modePhoto.text = if (on) "MEMORY" else "PHOTO"
        b.modeVideo.text = if (on) "TAPE" else "VIDEO"
        if (on) {
            b.firmwareOsd.navigator = navigator
            b.firmwareOsd.listener = this
            navigator.lamp = Lamp.TAPE
            host.setPhotoMode(false)
            powerOn()
        } else {
            b.firmwareOsd.listener = null
        }
        updateLampLabels()
    }

    /** POWER switch moved to a camera lamp: date/time ~5 s, REMAINING AUTO ~8 s. */
    private fun powerOn() {
        b.firmwareOsd.showDateTime()
        if (navigator.settings.cassette != null) b.firmwareOsd.showRemaining()
        else b.firmwareOsd.showMessage(MSG_INSERT_CASSETTE)
    }

    fun setLamp(target: Lamp) {
        if (!active || target == navigator.lamp) return
        if (host.isRecording()) host.requestStopRecording()
        val from = navigator.lamp
        if (from == Lamp.PLAY_EDIT) leavePlayback()
        navigator.lamp = target
        when (target) {
            Lamp.TAPE -> { host.setPhotoMode(false); if (from == Lamp.PLAY_EDIT) host.cameraOn(); powerOn() }
            Lamp.MEMORY -> { host.setPhotoMode(true); if (from == Lamp.PLAY_EDIT) host.cameraOn(); powerOn() }
            Lamp.PLAY_EDIT -> enterPlayback()
        }
        updateLampLabels()
        b.firmwareOsd.invalidate()
    }

    private fun updateLampLabels() {
        if (!active) {
            b.modePlay.setTextColor(color(R.color.hud_dim))
            return
        }
        b.modeVideo.setTextColor(color(if (navigator.lamp == Lamp.TAPE) R.color.hud_accent else R.color.hud_dim))
        b.modePhoto.setTextColor(color(if (navigator.lamp == Lamp.MEMORY) R.color.hud_accent else R.color.hud_dim))
        b.modePlay.setTextColor(color(if (navigator.lamp == Lamp.PLAY_EDIT) R.color.hud_accent else R.color.hud_dim))
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    // ================= cassette =================

    private fun saveSettings(s: FirmwareSettings) {
        navigator.replaceSettings(s)
        store.firmware = s
        b.firmwareOsd.invalidate()
    }

    /** Phone-side stand-in for the OPEN/EJECT lever (long-press on the TAPE lamp). */
    fun ejectOrInsert() {
        if (!active || host.isRecording()) return
        val s = navigator.settings
        if (s.cassette != null) {
            saveSettings(s.copy(cassette = null))
            b.firmwareOsd.showMessage(MSG_INSERT_CASSETTE)
            Toast.makeText(context, "Cassette ejected. Long-press TAPE to insert a new DVM60.", Toast.LENGTH_SHORT).show()
        } else {
            saveSettings(s.copy(cassette = VirtualCassette.fresh()))
            b.firmwareOsd.showRemaining()
            Toast.makeText(context, "New DVM60 cassette inserted.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Called before START: false (with an on-screen warning) if there is no usable tape. */
    fun canStartRecording(): Boolean {
        if (!active || navigator.lamp != Lamp.TAPE) return !active
        val cassette = navigator.settings.cassette
        val msg = when {
            cassette == null -> MSG_INSERT_CASSETTE
            cassette.isAtEnd -> MSG_TAPE_END
            else -> null
        }
        if (msg != null) {
            b.firmwareOsd.showMessage(msg)
            playBeep(Beep.WARNING)
            return false
        }
        if (navigator.isMenuOpen) navigator.close()
        return true
    }

    fun onRecordingStarted() {
        if (!active) return
        playBeep(Beep.REC_START)
        b.firmwareOsd.sceneMs = 0
        b.firmwareOsd.recording = true
    }

    /** Recording progress; stops the recording when the virtual tape runs out. */
    fun onRecordingProgress(sceneMs: Long) {
        if (!active) return
        b.firmwareOsd.sceneMs = sceneMs
        val cassette = navigator.settings.cassette ?: return
        if (sceneMs >= cassette.remainingMs(navigator.settings.recMode)) {
            host.requestStopRecording()
            b.firmwareOsd.showMessage(MSG_TAPE_END)
            playBeep(Beep.WARNING)
        }
    }

    fun onRecordingFinished(sceneMs: Long, saved: Boolean) {
        if (!active) return
        playBeep(Beep.REC_STOP)
        b.firmwareOsd.recording = false
        b.firmwareOsd.sceneMs = 0
        val s = navigator.settings
        val cassette = s.cassette
        if (saved && cassette != null) saveSettings(s.copy(cassette = cassette.afterRecording(sceneMs, s.recMode)))
    }

    // ================= sounds =================

    private fun playBeep(sound: Beep) {
        if (navigator.settings.beepOn) beeps.play(sound)
    }

    override fun onBeep(beep: Beep) = playBeep(beep)

    // ================= navigation effects =================

    override fun onNavEffect(effect: NavEffect) {
        when (effect) {
            NavEffect.None -> Unit
            is NavEffect.SettingsChanged -> saveSettings(effect.settings)
            NavEffect.EndSearch -> endSearch()
        }
        b.firmwareOsd.invalidate()
    }

    /** END SEARCH: the virtual tape is always cued at the end of the recorded part in CAMERA
     *  mode, so the search only shows its message; in PLAY/EDIT it cues the last scene's end. */
    private fun endSearch() {
        if (navigator.lamp == Lamp.PLAY_EDIT) {
            if (clips.isNotEmpty()) {
                val last = clips.size - 1
                loadClip(last, play = false, seekToEndMs = clips[last].durationMs)
            }
            transport(FirmwareOsdView.Transport.STOP)
        }
        b.firmwareOsd.showMessage(MSG_END_SEARCH, 2_000)
    }

    // ================= PLAY/EDIT tape playback =================

    private data class Clip(val uri: Uri, val durationMs: Long, val takenMs: Long)

    private var clips: List<Clip> = emptyList()
    private var clipIndex = 0
    private var pausedSince = 0L

    private val positionTicker = object : Runnable {
        override fun run() {
            if (!cameraSuspended) return
            updatePlaybackPosition()
            if (b.firmwareOsd.transport == FirmwareOsdView.Transport.PAUSE &&
                System.currentTimeMillis() - pausedSince > PAUSE_LIMIT_MS
            ) {
                // "Playback automatically stops if pause is engaged for more than 3 minutes."
                b.playbackView.pause()
                transport(FirmwareOsdView.Transport.STOP)
            }
            main.postDelayed(this, 80)
        }
    }

    private fun enterPlayback() {
        host.cameraOff()
        b.playbackView.visibility = View.VISIBLE
        transport(FirmwareOsdView.Transport.STOP)
        b.firmwareOsd.playbackPositionMs = 0
        b.firmwareOsd.playbackClipTimeMs = null
        b.playbackView.setOnCompletionListener { onClipCompleted() }
        b.playbackView.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "playback error $what/$extra")
            transport(FirmwareOsdView.Transport.STOP)
            true
        }
        io.execute {
            val found = queryClips()
            main.post {
                if (!cameraSuspended) return@post
                clips = found
                if (clips.isNotEmpty()) loadClip(0, play = false)
                b.firmwareOsd.showRemaining()
                main.post(positionTicker)
            }
        }
    }

    private fun leavePlayback() {
        main.removeCallbacks(positionTicker)
        try { b.playbackView.stopPlayback() } catch (_: Throwable) {}
        b.playbackView.visibility = View.GONE
        clips = emptyList()
        transport(FirmwareOsdView.Transport.STOP)
    }

    fun onHostPause() {
        if (cameraSuspended && b.firmwareOsd.transport == FirmwareOsdView.Transport.PLAY) {
            b.playbackView.pause()
            transport(FirmwareOsdView.Transport.PAUSE)
        }
    }

    override fun onPlaybackCommand(command: FirmwareOsdView.PlaybackCommand) {
        if (clips.isEmpty()) return
        val v = b.playbackView
        when (command) {
            FirmwareOsdView.PlaybackCommand.PLAY_PAUSE -> {
                if (b.firmwareOsd.transport == FirmwareOsdView.Transport.PLAY) {
                    v.pause(); transport(FirmwareOsdView.Transport.PAUSE)
                } else {
                    v.start(); transport(FirmwareOsdView.Transport.PLAY)
                    b.firmwareOsd.showRemaining()
                }
            }
            FirmwareOsdView.PlaybackCommand.STOP -> {
                v.pause(); transport(FirmwareOsdView.Transport.STOP)
            }
            FirmwareOsdView.PlaybackCommand.FF -> {
                if (clipIndex < clips.size - 1) loadClip(clipIndex + 1, play = isPlaying())
            }
            FirmwareOsdView.PlaybackCommand.REW -> {
                if (v.currentPosition > 2_000 || clipIndex == 0) loadClip(clipIndex, play = isPlaying())
                else loadClip(clipIndex - 1, play = isPlaying())
            }
        }
        updatePlaybackPosition()
    }

    private fun isPlaying() = b.firmwareOsd.transport == FirmwareOsdView.Transport.PLAY

    private fun transport(t: FirmwareOsdView.Transport) {
        if (t == FirmwareOsdView.Transport.PAUSE) pausedSince = System.currentTimeMillis()
        b.firmwareOsd.transport = t
    }

    private fun loadClip(index: Int, play: Boolean, seekToEndMs: Long = 0) {
        clipIndex = index
        val clip = clips[index]
        b.playbackView.setVideoURI(clip.uri)
        b.playbackView.setOnPreparedListener {
            // seekTo(1) shows the first frame of a stopped clip instead of a black surface.
            val target = if (seekToEndMs > 0) (seekToEndMs - 200).coerceAtLeast(0).toInt() else 1
            b.playbackView.seekTo(target)
            if (play) {
                b.playbackView.start()
                transport(FirmwareOsdView.Transport.PLAY)
            }
        }
        b.firmwareOsd.playbackClipTimeMs = clip.takenMs
        updatePlaybackPosition()
    }

    private fun onClipCompleted() {
        if (clipIndex < clips.size - 1) loadClip(clipIndex + 1, play = true)
        else transport(FirmwareOsdView.Transport.STOP)
    }

    private fun updatePlaybackPosition() {
        if (clips.isEmpty()) return
        val before = clips.take(clipIndex).sumOf { it.durationMs }
        val pos = try { b.playbackView.currentPosition.toLong() } catch (_: Throwable) { 0L }
        b.firmwareOsd.playbackPositionMs = before + pos
    }

    /** Recorded scenes in Movies/CCDCam, oldest first: the order they were "taped". */
    @Suppress("DEPRECATION") // MediaColumns.DATA is the only path filter before API 29
    private fun queryClips(): List<Clip> {
        val out = ArrayList<Clip>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Movies/CCDCam%")
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/CCDCam/%")
        }
        try {
            context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} ASC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val dur = c.getLong(durCol)
                    if (dur <= 0) continue
                    val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000)
                    out += Clip(ContentUris.withAppendedId(uri, c.getLong(idCol)), dur, taken)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clip query failed", t)
        }
        return out
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        beeps.release()
        io.shutdownNow()
    }

    companion object {
        private const val TAG = "HC90eController"
        private const val PAUSE_LIMIT_MS = 3L * 60_000

        // Exact wording from the DCR-HC90E Operating Guide (p. 98-99) / HC90 guide p. 31.
        const val MSG_INSERT_CASSETTE = "Insert a cassette"
        const val MSG_TAPE_END = "The tape has reached the end."
        const val MSG_END_SEARCH = "END SEARCH EXECUTING"
    }
}
