<p align="center">
  <img src="docs/icon.png" width="112" alt="CCDCam">
</p>

<h1 align="center">CCDCam</h1>

<p align="center">An open-source camcorder filter for Android. Your camera, run through a 90s Hi8 / MiniDV look, baked into every photo and video.</p>

<p align="center">
  <img src="https://github.com/sturq/ccdcam/actions/workflows/build.yml/badge.svg" alt="build status">
</p>

---

> **This fork is a Sony DCR-HC90E (PAL) camcorder emulator.** The original CCDCam profile and HUD
> were removed; the app now emulates one camera: the HC90E picture (720×576 sampling grid, 576
> scanlines, CCD look) and its touch-panel firmware (P-MENU, MENU drum, virtual Mini DV
> cassette, PLAY/EDIT). References and what is documented vs approximated:
> `docs/hc90e-reference.md`, `docs/hc90e-ui-references.md`. Recorded files are CameraX MP4
> (H.264 + AAC, 16:9), not DV.
>
> OSD font: based on **PhoenixVGA 8x14** from *The Ultimate Oldschool PC Font Pack* by VileR
> (int10h.org), CC BY-SA 4.0, adapted towards the Sony OSD; used for the whole emulated interface.

CCDCam runs the live camera feed through a GLSL fragment shader that fakes the look of a
90s Sony Handycam / Hi8 / MiniDV: chunky low-res chroma, vertical highlight smear,
horizontal flare, scanlines, a warm grade, and a vignette. Photos and video are saved with
the filter baked into the pixels. It is a free alternative to the paid camcorder-filter apps.

## Examples

Forest, shot on a Pixel through CCDCam:

<p>
  <img src="docs/examples/forest_1.jpg" width="32%" alt="forest 1" />
  <img src="docs/examples/forest_2.jpg" width="32%" alt="forest 2" />
  <img src="docs/examples/forest_3.jpg" width="32%" alt="forest 3" />
</p>
<p>
  <img src="docs/examples/sample_4.jpg" width="32%" alt="sample 4" />
  <img src="docs/examples/sample_6.jpg" width="32%" alt="sample 6" />
  <img src="docs/examples/sample_7.jpg" width="32%" alt="sample 7" />
</p>
<p>
  <img src="docs/examples/sample_8.jpg" width="49%" alt="sample 8" />
  <img src="docs/examples/sample_9.jpg" width="49%" alt="sample 9" />
</p>

## Features

- **Live filtered preview** at full display refresh rate (60 to 120 fps)
- **Photo mode**: single JPEG snapshot of the filtered frame, saved to `Pictures/CCDCam/`
- **Video mode**: H.264 MP4 plus AAC audio recorded by CameraX `Recorder`, filter baked in via a CameraX `CameraEffect`, saved to `Movies/CCDCam/`
- **Pinch-to-zoom** clamped to the lens's min/max
- **Camcorder HUD overlay**: corner viewfinder brackets, `HH:MM:SS:FF` timecode, `STBY`/`REC` indicator with a blinking red dot, tape mode label, battery icon, live date stamp
- **Front/back camera flip**
- **Material You adaptive icon** with a monochrome layer that picks up the system wallpaper colour on Android 13+
- **Python sim** (`tools/sim.py`): a numpy reimplementation of the shader, so you can iterate the look on a JPEG or MP4 without rebuilding the APK

## What the shader does

`app/src/main/assets/shaders/ccd.frag`, in order:

1. UV quantization to a 720x720 cell grid, so GPU bilinear inside each cell plus cell-center sampling reproduces a downsample-then-nearest-upsample Hi8 chunkiness without an offscreen pass
2. Chroma shift on R/B sampling for NTSC colour bleed
3. Vertical CCD smear, where bright pixels bleed up and down their column
4. Horizontal flare around extreme highlights
5. Per-frame chroma noise on R and B
6. Luma grain
7. Black lift, warm grade, slight desaturation
8. Scanline modulation tied to the vertical line count
9. Radial vignette

The look parameters are uniforms fed every frame from the active camera profile
(`app/src/main/java/at/sturq/ccdcam/profile/Profiles.kt`). The same values for the default
CCDCam profile live in `tools/sim.py`, so changes can be previewed in Python before rebuilding.

## Architecture

- **CameraX 1.4** binds `Preview` (shown in a `PreviewView`) and `VideoCapture<Recorder>` together with one `CameraEffect` in a `UseCaseGroup` (`MainActivity.bindCamera()`)
- **`CcdEffect`** attaches **`CcdSurfaceProcessor`** to the `PREVIEW` and `VIDEO_CAPTURE` targets. The processor owns an EGL context, receives camera frames in an OES `SurfaceTexture`, and draws `ccd.frag` once per output (preview surface and video encoder surface) with the per-output transform from `SurfaceOutput.updateTransformMatrix`, keeping the camera timestamp via `eglPresentationTimeANDROID`
- **Video and audio** are encoded and muxed by CameraX `Recorder` (H.264 + AAC, `Quality.HD`, `withAudioEnabled()`), written through `MediaStoreOutputOptions`
- **Photo capture** takes `PreviewView.bitmap` (already filtered), rotates it to the device orientation, and writes a JPEG via `MediaStore`
- **HUD** is plain Android views over the preview (`activity_main.xml`, `BatteryHudView`); it is not part of the recorded video or the saved photo

## Build

```
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK, signed with the bundled debug keystore
./gradlew testDebugUnitTest    # JVM unit tests
```

The Gradle wrapper pins Gradle 8.9 and needs JDK 17 (AGP 8.5).

Requires Android 7.0 (API 24) or newer. CI builds on every push, and release APKs are
produced on `v*` tags via `.github/workflows/release.yml`.

## Install

The latest pre-built APK is on the [Releases page](https://github.com/sturq/ccdcam/releases)
(signed with the bundled debug keystore, so installing requires allowing unknown sources).
Once published, F-Droid will be the recommended channel.

## Tweaking the look

Edit the profile values in `app/src/main/java/at/sturq/ccdcam/profile/Profiles.kt`, and mirror
the `CCDCAM_DEFAULT` values in `tools/sim.py` (kept in sync by hand). Run the sim on a sample frame:

```
python tools/sim.py input.jpg output.jpg
python tools/sim.py input.mp4 output.mp4   # requires ffmpeg
python tools/test_sim.py                    # invariants the shader sim has to satisfy
```

## License

[GPL-3.0-only](LICENSE). A fork or any project using this code has to be under GPL-3.0
(or a compatible license) with source available to users. The Hi8 look should stay free
for everyone.
