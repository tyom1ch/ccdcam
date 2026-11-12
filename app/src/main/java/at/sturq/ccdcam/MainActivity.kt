package at.sturq.ccdcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.FocusMeteringAction
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import at.sturq.ccdcam.firmware.FirmwareSettings
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.camera.FrameDecimator
import at.sturq.ccdcam.camera.ByteStore
import at.sturq.ccdcam.camera.FrameRatePlan
import at.sturq.ccdcam.camera.Mp4Rotation
import at.sturq.ccdcam.geometry.EffectGeometry
import at.sturq.ccdcam.databinding.ActivityMainBinding
import at.sturq.ccdcam.firmware.Hc90eController
import at.sturq.ccdcam.firmware.Lamp
import at.sturq.ccdcam.profile.Profiles
import at.sturq.ccdcam.settings.AspectSetting
import at.sturq.ccdcam.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DCR-HC90E emulator: CameraX pipeline (PreviewView + VideoCapture<Recorder>) with the CCD
 * shader injected via [CcdEffect] (a SurfaceProcessor-based CameraEffect) and the emulated
 * touch-panel firmware drawn on top ([Hc90eController]). Rotation of the recorded video is
 * delegated to CameraX; the shader's picture geometry follows the same rotation.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    /** One processor (and effect) per target: the preview and the recording are separate
     *  streams, so the burned-in OSD can go into the MP4 only. */
    private lateinit var previewProcessor: CcdSurfaceProcessor
    private lateinit var videoProcessor: CcdSurfaceProcessor
    private lateinit var previewEffect: CcdEffect
    private lateinit var videoEffect: CcdEffect

    /** HC90E firmware display, virtual tape and PLAY/EDIT playback. */
    private lateinit var hc: Hc90eController

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    /** Picture aspect (WIDE SELECT): 16:9 or 4:3, framed in the shader. */
    private var wide = true
    private var mode = Mode.VIDEO

    @Volatile private var lastSurfaceRotation: Int = Surface.ROTATION_0
    /** Rotation the current recording was started with. VideoCapture ignores targetRotation
     *  changes during a recording, so picture-space effects freeze to this value too. */
    private var recordingRotation: Int? = null
    /** CW display rotation of the clip being recorded; written into the MP4 on Finalize. */
    private var recordingDisplayCw = 0
    /** CW degrees to apply to PreviewView.bitmap before saving a photo (the bitmap is
     *  always in display orientation; we need to bake the physical rotation in). */
    @Volatile private var photoRotationCw: Int = 0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when {
                    orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    orientation < 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                photoRotationCw = when {
                    orientation < 45 -> 0
                    orientation < 135 -> 90
                    orientation < 225 -> 180
                    orientation < 315 -> 270
                    else -> 0
                }
                // The firmware display turns with the phone (the activity itself stays portrait,
                // so rotating never recreates it or rebinds CameraX) — but not while recording:
                // like the picture, it stays in the orientation the recording started with.
                if (recordingRotation == null) binding.firmwareOsd.deviceRotationCw = photoRotationCw
                if (rot != lastSurfaceRotation) {
                    lastSurfaceRotation = rot
                    if (recordingRotation == null) updateFraming()
                }
            }
        }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Shows the measured frame rate on the body (outside the emulated LCD), once a second. */
    private val fpsTicker = object : Runnable {
        override fun run() {
            // Recording: measured rate of the MP4 stream; standby: the target.
            val fps = if (recording != null) videoProcessor.frameRateMeter.roundedFps() else null
            binding.fpsTxt.text = if (fps != null) "PAL ${fps}p" else "PAL 25p"
            uiHandler.postDelayed(this, 1_000)
        }
    }

    private lateinit var scaleDetector: ScaleGestureDetector

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Camera + mic gate the camera; storage (API <= 28 only) gates saving, so a denial
        // there must not block the preview.
        if (hasPerms()) bindCamera()
        else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
        if (result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false) {
            Toast.makeText(this, R.string.perm_storage_denied, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        wide = settings.aspect == AspectSetting.RATIO_16_9

        previewProcessor = CcdSurfaceProcessor(this, name = "CcdPreview")
        videoProcessor = CcdSurfaceProcessor(this, name = "CcdVideo", burnsOsd = true, decimator = FrameDecimator())
        for (p in listOf(previewProcessor, videoProcessor)) p.profile = Profiles.HC90E_PAL
        previewEffect = CcdEffect(previewProcessor, CameraEffect.PREVIEW)
        videoEffect = CcdEffect(videoProcessor, CameraEffect.VIDEO_CAPTURE)
        hc = Hc90eController(this, binding, settings, object : Hc90eController.Host {
            override fun isRecording() = recording != null
            override fun requestStopRecording() = stopRecording()
            override fun cameraOn() = bindCamera()
            override fun cameraOff() {
                cameraProvider?.unbindAll()
                camera = null
            }
            override fun setPhotoMode(photo: Boolean) = setMode(if (photo) Mode.PHOTO else Mode.VIDEO)
            override fun setNightShot(on: Boolean) {
                previewProcessor.nightShot = on
                videoProcessor.nightShot = on
                applyCameraSettings()
            }
            override fun applyCameraSettings() = this@MainActivity.applyCameraSettings()
            override fun spot(focus: Boolean, viewX: Float, viewY: Float) {
                val cam = camera ?: return
                val point = binding.previewView.meteringPointFactory.createPoint(viewX, viewY)
                val flags = if (focus) FocusMeteringAction.FLAG_AF else FocusMeteringAction.FLAG_AE
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, flags).disableAutoCancel().build(),
                )
            }
            override fun spotAuto() {
                camera?.cameraControl?.cancelFocusAndMetering()
            }
            override fun setFade(amount: Float, white: Boolean) {
                for (p in listOf(previewProcessor, videoProcessor)) { p.fade = amount; p.fadeWhite = white }
            }
            override fun setLight(on: Boolean): Boolean {
                val cam = camera ?: return false
                if (!cam.cameraInfo.hasFlashUnit()) return false
                cam.cameraControl.enableTorch(on)
                return true
            }
            override fun videoOsdWidth() = videoOsdWidthFor(wide)
            override fun setVideoOsd(quads: List<at.sturq.ccdcam.osd.OsdQuad>?) {
                videoProcessor.osdQuads = quads
            }
        })
        applyWideLabel()

        binding.modePhoto.setOnClickListener { hc.setLamp(Lamp.MEMORY) }
        binding.modeVideo.setOnClickListener { hc.setLamp(Lamp.TAPE) }
        binding.modePlay.setOnClickListener { hc.setLamp(Lamp.PLAY_EDIT) }
        binding.modeVideo.setOnLongClickListener {
            hc.ejectOrInsert()   // stand-in for the OPEN/EJECT lever
            true
        }
        binding.wideSelectBtn.setOnClickListener { toggleWide() }
        binding.nightShotBtn.setOnClickListener { hc.toggleNightShot() }
        binding.lightBtn.setOnClickListener { hc.toggleLight() }
        binding.flipBtn.setOnClickListener {
            if (recording != null) {
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        binding.shutterBtn.setOnClickListener {
            if (hc.cameraSuspended) return@setOnClickListener   // PLAY/EDIT: START/STOP inactive
            when (mode) {
                Mode.PHOTO -> capturePhoto()
                Mode.VIDEO -> toggleRecording()
            }
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val newRatio = (zs.zoomRatio * detector.scaleFactor)
                    .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(newRatio)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, ev: MotionEvent ->
            scaleDetector.onTouchEvent(ev)
            true
        }

        hc.start()
        val missing = missingPerms()
        if (missing.isNotEmpty()) permLauncher.launch(missing) else bindCamera()
    }

    private fun setMode(m: Mode) {
        if (recording != null && m != Mode.VIDEO) stopRecording()
        mode = m
        binding.shutterBtn.setBackgroundResource(
            if (m == Mode.PHOTO) R.drawable.shutter_photo else R.drawable.shutter_video
        )
    }

    private fun isGranted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /** Camera + mic: required to bind the camera at all. */
    private fun hasPerms() = REQUIRED_PERMS.all(this::isGranted)

    /** Everything we ask for, including legacy storage on API <= 28 (MediaStore writes need it there). */
    private fun missingPerms(): Array<String> {
        val wanted = if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P)
            REQUIRED_PERMS + Manifest.permission.WRITE_EXTERNAL_STORAGE
        else REQUIRED_PERMS
        return wanted.filterNot(this::isGranted).toTypedArray()
    }

    private fun bindCamera() {
        if (!hasPerms()) return
        if (hc.cameraSuspended) return   // PLAY/EDIT: camera section off
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            // Preview and video are both 16:9 so the emulated 4:3/16:9 picture is framed
            // identically in both; the whole stream is shown (FIT_CENTER).
            binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
            // WIDE 16:9 -> 16:9 streams; 4:3 -> 4:3 streams, so the recorded file has no
            // pillar bars. The LCD stays 16:9 (the preview shows 4:3 with bars, like the HC90).
            val streamAspect = if (wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            binding.firmwareOsd.regionAspectInView = if (wide) 9f / 16f else 3f / 4f

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // PAL 25 fps: the camera keeps its native rate (a fixed [25,25] request froze it);
            // the video processor converts to 25 fps CFR (FrameDecimator).
            val ranges = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?.supportedFrameRateRanges.orEmpty().map { it.lower to it.upper }
            Log.i(TAG, "AE fps ranges ${FrameRatePlan.describe(ranges)}; PAL 25p by frame-grid conversion")

            preview = Preview.Builder()
                .setTargetAspectRatio(streamAspect)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .setAspectRatio(streamAspect)
                .build()
            videoCapture = VideoCapture.Builder(recorder)
                .build()
                // Fixed pipeline orientation: the app, not CameraX, turns picture + OSD for
                // the way the phone is held and writes the MP4 rotation itself (Mp4Rotation).
                .also { it.targetRotation = Surface.ROTATION_0 }

            val group = UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .addUseCase(videoCapture!!)
                .addEffect(previewEffect)
                .addEffect(videoEffect)
                .build()

            try {
                camera = provider.bindToLifecycle(this, selector, group)
                hc.reapplyBodySwitches()
                updateFraming()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** WIDE SELECT: the picture aspect is a shader framing; the camera stays as bound. */
    private fun toggleWide() {
        if (recording != null) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        wide = !wide
        settings.aspect = if (wide) AspectSetting.RATIO_16_9 else AspectSetting.RATIO_4_3
        applyWideLabel()
        bindCamera()      // stream aspect changes with WIDE SELECT
    }

    /** OSD width of the recorded frame: the picture's own aspect × the LCD height. */
    private fun videoOsdWidthFor(wide: Boolean) =
        at.sturq.ccdcam.osd.OsdScene.LCD_H * (if (wide) 16f / 9f else 4f / 3f)

    private fun applyWideLabel() {
        binding.wideSelectBtn.text = if (wide) "WIDE 16:9" else "WIDE 4:3"
    }

    /**
     * Sends the current framing to both processors: whole 16:9 stream,
     * picture aspect from WIDE SELECT, the emulated 16:9 LCD band, and the CW rotation CameraX
     * uses to make the sensor image upright for the current (or recording) target rotation.
     */
    private fun updateFraming() {
        val cam = camera ?: return
        val rotation = recordingRotation ?: lastSurfaceRotation
        val preview = CcdSurfaceProcessor.Framing(
            regionAspect = null,   // each processor's output is its own stream
            pictureAspect = if (wide) 16f / 9f else 4f / 3f,
            sensorRotationDegrees = cam.cameraInfo.getSensorRotationDegrees(rotation),
            lcdAspect = 16f / 9f,  // the emulated LCD is 16:9
        )
        previewProcessor.framing = preview
        // The recording is the picture itself: no LCD band, so no bars in the file.
        videoProcessor.framing = preview.copy(lcdAspect = null)
        videoProcessor.osdLcdWidth = videoOsdWidthFor(wide)
    }

    private fun capturePhoto() {
        val bmp = binding.previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show()
            return
        }
        binding.shutterBtn.isEnabled = false
        val rot = photoRotationCw
        ioScope.launch {
            // Rotate to physical orientation so floor ends up at the bottom of the file.
            val upright = if (rot == 0) bmp else {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val uri = savePhoto(upright)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (uri != null) "Saved to ${Hc90eController.PICTURES_DIR}" else "Save failed",
                    Toast.LENGTH_SHORT,
                ).show()
                binding.shutterBtn.isEnabled = true
            }
        }
    }

    private fun savePhoto(bmp: Bitmap): android.net.Uri? {
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Hc90eController.PICTURES_DIR)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: return null
        contentResolver.openOutputStream(uri)?.use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
        } ?: return null
        return uri
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let {
            if (hc.onStopRequested()) return    // FADER: fades out first, then stops
            stopRecording()
            return
        }
        if (!hc.canStartRecording()) return
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Hc90eController.MOVIES_DIR)
            }
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()

        recordingRotation = lastSurfaceRotation
        recordingDisplayCw = camera?.let {
            EffectGeometry.uprightCwDegrees(
                it.cameraInfo.getSensorRotationDegrees(lastSurfaceRotation),
                videoProcessor.outputRotationCw, mirrored = false,
            )
        } ?: 0
        updateFraming()
        recording = vc.output
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video_recording)
                        hc.onRecordingStarted()
                    }
                    is VideoRecordEvent.Status -> {
                        hc.onRecordingProgress(event.recordingStats.recordedDurationNanos / 1_000_000)
                    }
                    is VideoRecordEvent.Finalize -> {
                        hc.onRecordingFinished(
                            event.recordingStats.recordedDurationNanos / 1_000_000,
                            saved = event.outputResults.outputUri != android.net.Uri.EMPTY,
                        )
                        if (!event.hasError()) applyClipRotation(event.outputResults.outputUri, recordingDisplayCw)
                        recordingRotation = null
                        updateFraming()
                        binding.firmwareOsd.deviceRotationCw = photoRotationCw   // follow the phone again
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video)
                        val msg = if (event.hasError())
                            "Save error: ${event.error}"
                        else
                            "Saved to ${Hc90eController.MOVIES_DIR}"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    /** Writes the display rotation into the saved MP4 (in place, 36 bytes). */
    private fun applyClipRotation(uri: android.net.Uri, cwDegrees: Int) {
        if (uri == android.net.Uri.EMPTY) return
        ioScope.launch {
            try {
                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    // Channels on the pfd's descriptor; the pfd owns and closes it.
                    val rd = java.io.FileInputStream(pfd.fileDescriptor).channel
                    val wr = java.io.FileOutputStream(pfd.fileDescriptor).channel
                    run {
                        run {
                            val store = object : ByteStore {
                                override val size get() = rd.size()
                                override fun read(position: Long, dst: ByteArray) {
                                    val buf = java.nio.ByteBuffer.wrap(dst)
                                    var p = position
                                    while (buf.hasRemaining()) {
                                        val n = rd.read(buf, p)
                                        if (n < 0) throw java.io.EOFException()
                                        p += n
                                    }
                                }
                                override fun write(position: Long, src: ByteArray) {
                                    val buf = java.nio.ByteBuffer.wrap(src)
                                    var p = position
                                    while (buf.hasRemaining()) p += wr.write(buf, p)
                                }
                            }
                            val ok = Mp4Rotation.setVideoRotation(store, cwDegrees)
                            Log.i(TAG, "clip rotation $cwDegrees written=$ok")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clip rotation failed", t)
            }
        }
    }

    /**
     * EXPOSURE and WHITE BAL. from the firmware settings. NightShot lifts exposure to half the
     * positive range (soft-shouldered in the shader) and wins over EXPOSURE while on.
     */
    private fun applyCameraSettings() {
        val cam = camera ?: return
        val s = hc.navigator.settings
        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            val range = es.exposureCompensationRange
            val index = when {
                hc.nightShotOn -> range.upper / 2
                s.exposure == FirmwareSettings.EXPOSURE_AUTO -> 0
                else -> {
                    val ev = s.exposure.toIntOrNull() ?: 0
                    Math.round(ev / es.exposureCompensationStep.toFloat()).coerceIn(range.lower, range.upper)
                }
            }
            cam.cameraControl.setExposureCompensationIndex(index)
        }
        applyWhiteBalance(cam, s.whiteBal)
    }

    /** AUTO / ONE PUSH (AWB locked at the current estimate) / INDOOR / OUTDOOR. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyWhiteBalance(cam: Camera, wb: String) {
        val b = CaptureRequestOptions.Builder()
        val mode = when (wb) {
            FirmwareSettings.WB_INDOOR -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            FirmwareSettings.WB_OUTDOOR -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, wb == FirmwareSettings.WB_ONE_PUSH)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(fpsTicker)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        if (recording != null) stopRecording()
        hc.onHostPause()
        uiHandler.removeCallbacks(fpsTicker)
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hc.release()
        previewProcessor.release()
        videoProcessor.release()
    }

    companion object {
        private const val TAG = "HC90eMain"
        private val REQUIRED_PERMS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
