package at.sturq.ccdcam

import androidx.camera.core.CameraEffect

/**
 * Binds a [CcdSurfaceProcessor] to one CameraX target. The app uses one effect for PREVIEW and
 * one for VIDEO_CAPTURE: with single-target effects CameraX does not wrap the use cases in
 * StreamSharing, so the recording gets its own output and the burned-in OSD stays out of the
 * preview (the public API has no per-target output option for one multi-target effect).
 * IMAGE_CAPTURE is not used: photos are taken from PreviewView.bitmap.
 */
class CcdEffect(processor: CcdSurfaceProcessor, targets: Int) : CameraEffect(
    targets,
    processor.executor,
    processor,
    { /* error listener: nothing actionable */ },
)
