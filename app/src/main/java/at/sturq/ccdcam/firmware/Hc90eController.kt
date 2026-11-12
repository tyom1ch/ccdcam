package at.sturq.ccdcam.firmware

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.R
import at.sturq.ccdcam.databinding.ActivityMainBinding
import at.sturq.ccdcam.osd.OsdLayout
import at.sturq.ccdcam.osd.OsdQuad
import at.sturq.ccdcam.osd.OsdResources
import at.sturq.ccdcam.osd.OsdScene
import at.sturq.ccdcam.settings.SettingsStore
import java.util.concurrent.Executors

/**
 * Glue between MainActivity and the emulated HC90E firmware: drives the firmware display,
 * keeps the virtual cassette, plays control sounds and runs
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
        /** Indicator quads to burn into the recording, or null for none (BURN OSD OFF). */
        fun setVideoOsd(quads: List<OsdQuad>?)
        /** OSD width (LCD units) of the recorded frame: 1024 for 16:9, 768 for 4:3. */
        fun videoOsdWidth(): Float
        /** NightShot look on/off for both processors (+ exposure lift). */
        fun setNightShot(on: Boolean)
        /** Phone torch ("LIGHT"). Returns false if the camera has no flash unit. */
        fun setLight(on: Boolean): Boolean
        /** Exposure + white balance from the firmware settings (and NightShot). */
        fun applyCameraSettings()
        /** SPOT FOCUS / SPOT METER at preview-view coordinates; [auto] = back to automatic. */
        fun spot(focus: Boolean, viewX: Float, viewY: Float)
        fun spotAuto()
        /** FADER on both processors. */
        fun setFade(amount: Float, white: Boolean)
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

    /** Power on in CAMERA-TAPE, like switching the camcorder on. */
    fun start() {
        if (active) return
        active = true
        b.firmwareOsd.navigator = navigator
        b.firmwareOsd.listener = this
        navigator.lamp = Lamp.TAPE
        host.setPhotoMode(false)
        powerOn()
        updateLampLabels()
        main.post(videoOsdTicker)
    }

    // ================= burned-in OSD =================

    private val osdRes = OsdResources.get(context)
    private var lastVideoOsd: List<OsdQuad>? = null

    /**
     * Rebuilds the burned-in indicator layer and hands it to the video processor only when it
     * changed (≈1 Hz while recording: the counter). The GL side just draws the latest list.
     */
    private fun refreshVideoOsd() {
        val quads = if (navigator.settings.burnOsdOn && navigator.lamp == Lamp.TAPE) {
            b.firmwareOsd.indicators(SystemClock.uptimeMillis(), forVideo = true)?.let {
                OsdLayout.quads(OsdScene.build(it, forVideo = true, lcdW = host.videoOsdWidth()), osdRes.first, osdRes.second)
            }
        } else null
        if (quads != lastVideoOsd) {
            lastVideoOsd = quads
            host.setVideoOsd(quads)
        }
    }

    private val videoOsdTicker = object : Runnable {
        override fun run() {
            refreshVideoOsd()
            main.postDelayed(this, 200)
        }
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
    // ================= body switches: NIGHTSHOT, LIGHT =================

    var nightShotOn = false
        private set
    var lightOn = false
        private set

    /** NIGHTSHOT switch on the camcorder body: visual emulation only (no IR). */
    fun toggleNightShot() {
        nightShotOn = !nightShotOn
        host.setNightShot(nightShotOn)
        b.firmwareOsd.nightShot = nightShotOn
        updateBodyLabels()
        refreshVideoOsd()
    }

    /** LIGHT: the phone torch, independent of NightShot. */
    fun toggleLight() {
        val want = !lightOn
        if (host.setLight(want)) lightOn = want
        else Toast.makeText(context, "No light on this camera", Toast.LENGTH_SHORT).show()
        updateBodyLabels()
    }

    /** After a camera rebind the torch is off; re-apply both switches. */
    fun reapplyBodySwitches() {
        host.setNightShot(nightShotOn)
        host.applyCameraSettings()
        if (lightOn && !host.setLight(true)) lightOn = false
        updateBodyLabels()
    }

    private fun updateBodyLabels() {
        b.nightShotBtn.setTextColor(color(if (nightShotOn) R.color.hud_accent else R.color.hud_dim))
        b.lightBtn.setTextColor(color(if (lightOn) R.color.hud_accent else R.color.hud_dim))
    }

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
        if (navigator.settings.fader != FirmwareSettings.FADER_OFF) startFade(fadeInNow = true, thenStop = false)
        b.firmwareOsd.sceneMs = 0
        b.firmwareOsd.recording = true
        refreshVideoOsd()   // REC from the first frames, not after the next tick
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
        main.removeCallbacks(fadeTicker)
        fadeThenStop = false
        host.setFade(0f, false)
        b.firmwareOsd.recording = false
        b.firmwareOsd.sceneMs = 0
        refreshVideoOsd()
        val s = navigator.settings
        val cassette = s.cassette
        if (saved && cassette != null) saveSettings(s.copy(cassette = cassette.afterRecording(sceneMs, s.recMode)))
    }

    // ================= sounds =================

    private fun playBeep(sound: Beep) {
        if (navigator.settings.beepOn) beeps.play(sound)
    }

    override fun onBeep(beep: Beep) = playBeep(beep)

    override fun onSpot(itemId: String, viewX: Float, viewY: Float) =
        host.spot(itemId == HC90eMenu.SPOT_FOCUS, viewX, viewY)

    override fun onSpotAuto(itemId: String) = host.spotAuto()

    // ================= FADER =================

    private var fadeStart = 0L
    private var fadeIn = true
    private var fadeWhite = false
    private var fadeThenStop = false

    private val fadeTicker = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - fadeStart
            host.setFade(Fader.amount(elapsed, fadeIn), fadeWhite)
            if (!Fader.isDone(elapsed)) {
                main.postDelayed(this, 30)
            } else if (fadeThenStop) {
                fadeThenStop = false
                // One-shot, like the camcorder: the fader is cleared after fading out.
                saveSettings(navigator.settings.copy(fader = FirmwareSettings.FADER_OFF))
                host.requestStopRecording()
            }
        }
    }

    private fun startFade(fadeInNow: Boolean, thenStop: Boolean) {
        fadeWhite = navigator.settings.fader == FirmwareSettings.FADER_WHITE
        fadeIn = fadeInNow
        fadeThenStop = thenStop
        fadeStart = SystemClock.uptimeMillis()
        main.removeCallbacks(fadeTicker)
        main.post(fadeTicker)
    }

    /**
     * STOP pressed while recording. Returns true if the controller takes over (fade-out
     * first, then it stops the recording itself).
     */
    fun onStopRequested(): Boolean {
        if (fadeThenStop) return true                       // already fading out
        if (navigator.settings.fader == FirmwareSettings.FADER_OFF) return false
        startFade(fadeInNow = false, thenStop = true)
        return true
    }

    // ================= navigation effects =================

    override fun onNavEffect(effect: NavEffect) {
        when (effect) {
            NavEffect.None -> Unit
            is NavEffect.SettingsChanged -> {
                saveSettings(effect.settings)
                host.applyCameraSettings()
                refreshVideoOsd()
            }
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
        // Hide the camera viewport completely: its SurfaceView would otherwise keep the last
        // frame and may stack above the player's surface.
        b.previewView.visibility = View.INVISIBLE
        b.playbackContainer.visibility = View.VISIBLE
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
                // Like the tape after recording: cued at the latest scene; rewind goes back.
                if (clips.isNotEmpty()) loadClip(clips.size - 1, play = false)
                b.firmwareOsd.showRemaining()
                main.post(positionTicker)
            }
        }
    }

    private fun leavePlayback() {
        main.removeCallbacks(positionTicker)
        try { b.playbackView.stopPlayback() } catch (_: Throwable) {}
        b.playbackContainer.visibility = View.GONE
        b.previewView.visibility = View.VISIBLE
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

    /** Recorded scenes (Movies/Handycam and legacy Movies/CCDCam), oldest first: tape order. */
    @Suppress("DEPRECATION") // MediaColumns.DATA is the only path filter before API 29
    private fun queryClips(): List<Clip> {
        val out = ArrayList<Clip>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED,
        )
        // New scenes go to Movies/Handycam; scenes from before the rename stay in Movies/CCDCam.
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val col = MediaStore.Video.Media.RELATIVE_PATH
            "($col LIKE ? OR $col LIKE ?)" to arrayOf("$MOVIES_DIR%", "$LEGACY_MOVIES_DIR%")
        } else {
            val col = MediaStore.Video.Media.DATA
            "($col LIKE ? OR $col LIKE ?)" to arrayOf("%/$MOVIES_DIR/%", "%/$LEGACY_MOVIES_DIR/%")
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

        /** MediaStore folders: new recordings / photos, and the pre-rename video folder. */
        const val MOVIES_DIR = "Movies/Handycam"
        const val PICTURES_DIR = "Pictures/Handycam"
        const val LEGACY_MOVIES_DIR = "Movies/CCDCam"

        // Exact wording from the DCR-HC90E Operating Guide (p. 98-99) / HC90 guide p. 31.
        const val MSG_INSERT_CASSETTE = "Insert a cassette"
        const val MSG_TAPE_END = "The tape has reached the end."
        const val MSG_END_SEARCH = "END SEARCH EXECUTING"
    }
}
