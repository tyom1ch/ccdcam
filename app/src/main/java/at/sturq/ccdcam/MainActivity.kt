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
import at.sturq.ccdcam.firmware.CameraDataFormat
import at.sturq.ccdcam.firmware.CameraDataLog
import at.sturq.ccdcam.firmware.CameraDataStore
import at.sturq.ccdcam.audio.ClipAudioProcessor
import at.sturq.ccdcam.audio.SfxMixer
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.camera.FrameDecimator
import at.sturq.ccdcam.camera.FrameRatePlan
import at.sturq.ccdcam.geometry.EffectGeometry
import at.sturq.ccdcam.databinding.ActivityMainBinding
import at.sturq.ccdcam.firmware.Hc90eController
import at.sturq.ccdcam.firmware.Lamp
import at.sturq.ccdcam.profile.Profiles
import at.sturq.ccdcam.settings.AspectSetting
import at.sturq.ccdcam.settings.SettingsStore
import at.sturq.ccdcam.camera.ClipStore
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
    private val orientationStabilizer = at.sturq.ccdcam.camera.OrientationStabilizer()

    private lateinit var haptics: at.sturq.ccdcam.haptics.Hc90eHapticEngine
    private lateinit var studio: at.sturq.ccdcam.studio.StudioPanel
    private var studioState = at.sturq.ccdcam.studio.StudioState()

    private val cameraDataLog = CameraDataLog()
    @Volatile private var liveExposureNs = 0L
    @Volatile private var liveIso = 0
    @Volatile private var liveAperture = 0f
    private val captureDataCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult,
        ) {
            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
            val ap = result.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: CameraDataFormat.MIN_F
            cameraDataLog.onFrame(android.os.SystemClock.uptimeMillis(), exp, iso, ap)
            liveExposureNs = exp; liveIso = iso; liveAperture = ap
        }
    }

    /** Short fade out / in around the OSD turn instead of an instant flip. */
    private fun turnOsd(cw: Int) {
        val osd = binding.firmwareOsd
        if (osd.deviceRotationCw == cw) return
        osd.animate().cancel()
        osd.animate().alpha(0f).setDuration(110).withEndAction {
            osd.deviceRotationCw = cw
            binding.playbackView.deviceRotationCw = cw   // PLAY/EDIT picture turns with the OSD
            osd.animate().alpha(1f).setDuration(170).start()
        }.start()
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // Hysteresis + hold time: shaking the phone or holding it diagonally must not flip the UI.
                if (!orientationStabilizer.update(orientation, android.os.SystemClock.uptimeMillis())) return
                photoRotationCw = orientationStabilizer.rotationCw
                val rot = when (photoRotationCw) {
                    90 -> Surface.ROTATION_270
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                // The firmware display turns with the phone (the activity itself stays portrait,
                // so rotating never recreates it or rebinds CameraX) — but not while recording:
                // like the picture, it stays in the orientation the recording started with.
                if (recordingRotation == null) turnOsd(photoRotationCw)
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
        studioState = settings.studio
        haptics = at.sturq.ccdcam.haptics.Hc90eHapticEngine(this, binding.root).also { it.level = studioState.haptics }
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
                val result = cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, flags).disableAutoCancel().build(),
                )
                // Only a real lock is confirmed; a failed or cancelled focus is felt as refused.
                result.addListener({
                    val ok = runCatching { result.get().isFocusSuccessful }.getOrDefault(false)
                    if (focus) haptics.fire(
                        if (ok) at.sturq.ccdcam.haptics.HapticEvent.FOCUS_LOCKED
                        else at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED,
                    )
                }, ContextCompat.getMainExecutor(this@MainActivity))
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
            override fun haptic(event: at.sturq.ccdcam.haptics.HapticEvent) = haptics.fire(event)
        })
        applyWideLabel()

        binding.modePhoto.setOnClickListener { hc.setLamp(Lamp.MEMORY) }
        binding.modeVideo.setOnClickListener { hc.setLamp(Lamp.TAPE) }
        binding.modePlay.setOnClickListener { hc.setLamp(Lamp.PLAY_EDIT) }
        binding.modeVideo.setOnLongClickListener {
            hc.ejectOrInsert()   // stand-in for the OPEN/EJECT lever
            true
        }
        binding.wideSelectBtn.setOnClickListener {
            if (recording != null) haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED)
            else haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.SWITCH_TOGGLE)
            toggleWide()
        }
        studio = at.sturq.ccdcam.studio.StudioPanel(this, binding.studioContainer, studioCallbacks)
        binding.studioBtn.setOnClickListener {
            haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.BUTTON_PRESS)
            if (studio.isOpen) studio.close() else studio.open(studioState)
        }
        binding.nightShotBtn.setOnClickListener { hc.toggleNightShot() }
        binding.lightBtn.setOnClickListener { hc.toggleLight() }
        binding.flipBtn.setOnClickListener {
            if (recording != null) {
                haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED)
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.BUTTON_PRESS)
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        binding.shutterBtn.setOnClickListener {
            if (hc.cameraSuspended) {                              // PLAY/EDIT: START/STOP inactive
                haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED)
                return@setOnClickListener
            }
            // The key itself; the recording start/stop is felt when it really happens.
            haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.BUTTON_PRESS)
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
        recoverClips()
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

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
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
            binding.playbackView.regionAspect = binding.firmwareOsd.regionAspectInView

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // PAL 25 fps: the camera keeps its native rate (a fixed [25,25] request froze it);
            // the video processor converts to 25 fps CFR (FrameDecimator).
            val ranges = selector.filter(provider.availableCameraInfos).firstOrNull()
                ?.supportedFrameRateRanges.orEmpty().map { it.lower to it.upper }
            Log.i(TAG, "AE fps ranges ${FrameRatePlan.describe(ranges)}; PAL 25p by frame-grid conversion")

            val previewBuilder = Preview.Builder().setTargetAspectRatio(streamAspect)
            // DATA CODE: real exposure / sensitivity / aperture of the frames being recorded.
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(captureDataCallback)
            preview = previewBuilder
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
                cameraDataLog.isoMin = camera?.let {
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(it.cameraInfo)
                        .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        ?.lower
                } ?: 100
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

    /**
     * MEMORY: the photo is the picture exactly as the LCD shows it — read back from the preview
     * stream and cropped to the picture rectangle (no LCD band, no letterbox), turned upright for
     * the way the phone is held; date/time burned in when BURN OSD is ON in MEMORY.
     */
    private fun capturePhoto() {
        if (camera == null) {
            haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED)
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        binding.shutterBtn.isEnabled = false
        val rot = photoRotationCw
        val takenMs = System.currentTimeMillis()
        val date = hc.photoDateOn()
        previewProcessor.requestStill { frame ->
            ioScope.launch {
                val uri = try {
                    frame?.let {
                        val bmp = stillToBitmap(it, rot)
                        if (date) {
                            val lcdW = at.sturq.ccdcam.osd.OsdScene.LCD_H * bmp.width / bmp.height
                            at.sturq.ccdcam.osd.OsdCanvas.draw(
                                applicationContext, bmp, at.sturq.ccdcam.osd.OsdScene.buildPhoto(takenMs, lcdW), lcdW,
                            )
                        }
                        savePhoto(bmp, takenMs)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "photo failed", t)
                    null
                }
                withContext(Dispatchers.Main) {
                    if (uri == null) haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED)
                    Toast.makeText(
                        applicationContext,
                        if (uri != null) "Saved to ${Hc90eController.PICTURES_DIR}" else "Save failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                    binding.shutterBtn.isEnabled = true
                }
            }
        }
    }

    /** GL pixels (bottom row first) -> the picture rectangle, upright for how the phone is held. */
    private fun stillToBitmap(f: CcdSurfaceProcessor.StillFrame, rotCw: Int): Bitmap {
        val raw = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(f.rgba)   // row 0 of raw = GL bottom row
        // crop in GL coordinates (raw rows are GL rows); 1 px inset against the band edge
        val x0 = Math.ceil(f.left.toDouble()).toInt() + 1
        val x1 = Math.floor(f.right.toDouble()).toInt() - 1
        val r0 = Math.ceil(f.bottom.toDouble()).toInt() + 1
        val r1 = Math.floor(f.top.toDouble()).toInt() - 1
        val w = (x1 - x0).coerceIn(1, f.width - x0)
        val h = (r1 - r0).coerceIn(1, f.height - r0)
        val m = android.graphics.Matrix().apply {
            postScale(1f, -1f)                              // GL bottom-up -> top-down
            if (rotCw != 0) postRotate(rotCw.toFloat())     // upright for how the phone is held
        }
        val out = Bitmap.createBitmap(raw, x0, r0, w, h, m, true)
        if (out !== raw) raw.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun savePhoto(bmp: Bitmap, takenMs: Long): android.net.Uri? {
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(takenMs)
        val q = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, takenMs)
            if (q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Hc90eController.PICTURES_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        try {
            contentResolver.openOutputStream(uri)!!.use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, os) }
            runCatching {
                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                    val t = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(takenMs)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, t)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, t)
                    exif.saveAttributes()
                }
            }
            if (q) contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            bmp.recycle()
            return uri
        } catch (t: Throwable) {
            Log.w(TAG, "photo save failed", t)
            runCatching { contentResolver.delete(uri, null, null) }
            return null
        }
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
        // Recorded privately first; published to Movies/Handycam once finished (ClipStore).
        val recFile = ClipStore.recordingFile(this, name)
        val takenMs = System.currentTimeMillis()
        val opts = androidx.camera.video.FileOutputOptions.Builder(recFile).build()

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
                        hc.navigator.settings.let {
                            cameraDataLog.whiteBal = it.whiteBal
                            cameraDataLog.exposure = it.exposure
                        }
                        cameraDataLog.start(android.os.SystemClock.uptimeMillis())
                        haptics.fire(at.sturq.ccdcam.haptics.HapticEvent.REC_STARTED)
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video_recording)
                        hc.onRecordingStarted()
                    }
                    is VideoRecordEvent.Status -> {
                        hc.onRecordingProgress(event.recordingStats.recordedDurationNanos / 1_000_000)
                    }
                    is VideoRecordEvent.Finalize -> {
                        val dataCode = cameraDataLog.stop()
                        // Some errors (camera closed, storage full) still leave a valid file:
                        // whatever is playable is kept, like tape that was running.
                        val written = recFile.isFile && recFile.length() > 0
                        if (event.hasError()) Log.w(TAG, "recording finalized with error ${event.error}", event.cause)
                        hc.onRecordingFinished(event.recordingStats.recordedDurationNanos / 1_000_000, saved = written)
                        haptics.fire(
                            if (written) at.sturq.ccdcam.haptics.HapticEvent.REC_STOPPED
                            else at.sturq.ccdcam.haptics.HapticEvent.ACTION_REJECTED,
                        )
                        if (written) {
                            ioScope.launch {
                                try { CameraDataStore.save(hc.dataCodeDir(), "$name.mp4", dataCode) }
                                catch (t: Throwable) { Log.w(TAG, "data code save failed", t) }
                            }
                            finishClip(recFile, name, takenMs, recordingDisplayCw)
                        } else {
                            ClipStore.done(name)
                            recFile.delete()
                            Toast.makeText(this, "Save error: ${event.error}", Toast.LENGTH_SHORT).show()
                        }
                        recordingRotation = null
                        updateFraming()
                        turnOsd(photoRotationCw)   // follow the phone again
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video)
                    }
                }
            }
    }

    /**
     * After Finalize, off the main thread: display rotation, camcorder sound (AUDIO EMU, tape
     * noise), then one publish of the finished file to Movies/Handycam. Processing failure keeps
     * the original sound; the original is only lost if it is not playable at all.
     */
    private fun finishClip(file: java.io.File, name: String, takenMs: Long, cwDegrees: Int) {
        val s = hc.navigator.settings
        val profile = s.audioProfile()
        val tape = s.tapeNoise == FirmwareSettings.ON
        val keep = s.origAudio == FirmwareSettings.ORIG_AUDIO_KEEP
        val dir = Hc90eController.MOVIES_DIR
        ioScope.launch {
            val processed = ClipStore.processedFile(this@MainActivity, name)
            var published = false
            try {
                if (!ClipStore.isPlayable(file)) {
                    Log.w(TAG, "clip $name not playable, dropped")
                    file.delete()
                    return@launch
                }
                Log.i(TAG, "clip rotation $cwDegrees written=${ClipStore.applyRotation(file, cwDegrees)}")
                val ok = if (profile.bypass && !tape) false else {
                    // UI sounds are not mixed in: the phone's microphone already records them.
                    val mixer = SfxMixer(if (tape) hc.tapeLoopPcm() else null, SfxMixer.LOOP_GAIN, emptyList(), 0f)
                    ClipAudioProcessor.process(this@MainActivity, android.net.Uri.fromFile(file), processed, profile, cwDegrees, mixer)
                        .also { Log.i(TAG, "clip audio $profile ok=$it keep=$keep") }
                }
                published = when {
                    !ok -> ClipStore.publish(this@MainActivity, file, "$name.mp4", takenMs, dir) != null
                    keep -> ClipStore.publish(this@MainActivity, file, "$name.mp4", takenMs, dir) != null &&
                        ClipStore.publish(this@MainActivity, processed, "${name}_dv.mp4", takenMs, dir) != null
                    else -> ClipStore.publish(this@MainActivity, processed, "$name.mp4", takenMs, dir) != null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clip finish failed", t)
                // last resort: the recording as it is
                if (!published && file.isFile) {
                    published = ClipStore.publish(this@MainActivity, file, "$name.mp4", takenMs, dir) != null
                }
            } finally {
                processed.delete()
                if (published) file.delete()     // otherwise kept: published on next start
                ClipStore.done(name)
            }
            withContext(Dispatchers.Main) {
                val msg = if (published) "Saved to $dir" else "Save error: kept for retry"
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Recordings a killed process left unpublished (see ClipStore.recover). */
    private fun recoverClips() {
        ioScope.launch {
            val names = ClipStore.recover(this@MainActivity, Hc90eController.MOVIES_DIR) { name ->
                at.sturq.ccdcam.firmware.CameraDataFormat.startFromName(name) ?: System.currentTimeMillis()
            }
            if (names.isNotEmpty()) Log.i(TAG, "recovered clips $names")
        }
    }

    /**
     * EXPOSURE and WHITE BAL. from the firmware settings. NightShot lifts exposure to half the
     * positive range (soft-shouldered in the shader) and wins over EXPOSURE while on.
     */
    /** Picture: menu look (CCD LOOK, SHARPNESS) with the STUDIO sliders on top; both processors. */
    private fun applyLook() {
        val look = at.sturq.ccdcam.studio.StudioLook.apply(
            at.sturq.ccdcam.firmware.LookTweaks.profile(hc.navigator.settings), studioState,
        )
        for (p in listOf(previewProcessor, videoProcessor)) p.profile = look
    }

    private fun applyCameraSettings() {
        applyLook()
        val cam = camera ?: return
        val s = hc.navigator.settings
        val sns = s.superNs == FirmwareSettings.SUPER_NS_ON
        previewProcessor.superNightShot = sns
        videoProcessor.superNightShot = sns
        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            val range = es.exposureCompensationRange
            val index = when {
                hc.nightShotOn && sns -> range.upper           // Super NightShot: all the gain
                hc.nightShotOn -> range.upper / 2
                s.exposure == FirmwareSettings.EXPOSURE_AUTO -> 0
                else -> {
                    val ev = s.exposure.toIntOrNull() ?: 0
                    Math.round(ev / es.exposureCompensationStep.toFloat()).coerceIn(range.lower, range.upper)
                }
            }
            // STUDIO EXPOSURE shifts whatever the camcorder settings chose
            val shift = Math.round(studioState.value(at.sturq.ccdcam.studio.StudioSliders.EV) /
                es.exposureCompensationStep.toFloat())
            cam.cameraControl.setExposureCompensationIndex((index + shift).coerceIn(range.lower, range.upper))
        }
        applyCaptureOptions(cam, s.whiteBal)
    }

    /** Lens minimum focus distance in diopters (0 = fixed focus). */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun minFocusDiopters(): Float = camera?.let {
        androidx.camera.camera2.interop.Camera2CameraInfo.from(it.cameraInfo)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    } ?: 0f

    private val studioCallbacks = object : at.sturq.ccdcam.studio.StudioPanel.Callbacks {
        override fun onStudioChanged(state: at.sturq.ccdcam.studio.StudioState, picture: Boolean, camera: Boolean) {
            studioState = state
            haptics.level = state.haptics
            if (picture) applyLook()
            if (camera) applyCameraSettings()
        }
        override fun onZoom(linear: Float) {
            camera?.cameraControl?.setLinearZoom(linear)
        }
        override fun onHaptic(event: at.sturq.ccdcam.haptics.HapticEvent) = haptics.fire(event)
        override fun manualFocusSupported() = minFocusDiopters() > 0f
        override fun currentLinearZoom() = camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f
        override fun liveInfo(): String {
            val parts = ArrayList<String>()
            if (liveAperture > 0f) parts += "f/%.2f".format(Locale.US, liveAperture)
            parts += if (studioState.manualFocusActive) "Manual Focus" else "Auto Focus"
            if (liveExposureNs > 0) parts += "1/${Math.round(1e9 / liveExposureNs)}s"
            if (liveIso > 0) parts += "ISO $liveIso"
            return parts.joinToString("     ")
        }
        override fun onClosed(state: at.sturq.ccdcam.studio.StudioState) {
            studioState = state
            settings.studio = state
        }
    }

    /**
     * Camera2 request options (one set; each call replaces the previous): WHITE BAL. AUTO /
     * ONE PUSH (AWB locked at the current estimate) / INDOOR / OUTDOOR, and STUDIO manual focus.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyCaptureOptions(cam: Camera, wb: String) {
        val b = CaptureRequestOptions.Builder()
        val minFocus = minFocusDiopters()
        if (studioState.manualFocusActive && minFocus > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, studioState.focusFraction * minFocus)
        }
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
        settings.studio = studioState
        if (recording != null) stopRecording()
        hc.onHostPause()
        uiHandler.removeCallbacks(fpsTicker)
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hc.release()
        haptics.release()
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
