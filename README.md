<p align="center">
  <img src="docs/icon.png" width="112" alt="CCDCam HC90E">
</p>

<h1 align="center">CCDCam · HC90E PAL</h1>

<p align="center">A 2005 Mini DV camcorder in your Android phone: the picture, the touch-panel firmware, the tape and the sound of a Sony DCR-HC90E (PAL), emulated on top of CameraX.</p>

<img width="898" height="672" alt="photo_2026-11-13_14-01-55" src="https://github.com/user-attachments/assets/755b4610-9d13-4ce2-beae-355a15b8b7c8" />

<p align="center">
  <img src="https://github.com/tyom1ch/ccdcam/actions/workflows/build.yml/badge.svg" alt="build status">
</p>

---

Fork of [sturq/ccdcam](https://github.com/sturq/ccdcam) (GPL-3.0). The original CCDCam filter
profile and HUD were replaced by an emulation of one camera, the DCR-HC90E PAL. Not affiliated
with Sony; no Sony graphics, fonts or sounds are used. What is taken from the operating guide
and what is an approximation is listed in [`docs/hc90e-reference.md`](docs/hc90e-reference.md)
and [`docs/hc90e-ui-references.md`](docs/hc90e-ui-references.md).

## Install

Download the APK from the [Releases page](https://github.com/tyom1ch/ccdcam/releases) and allow
installing from unknown sources. Android 7.0 (API 24) or newer.

## What it does

**Picture** (GLSL, in the upright picture space)
- PAL DV sampling grid 720×576, 576 scanlines, horizontal detail limit, 4:2:0 chroma (BT.601)
- 25p PAL frame rate (frame-grid conversion from the camera's native rate, constant 40 ms)
- Interlace field weave, CCD noise, highlight flare, optional vertical CCD smear, vignette
- WIDE 16:9 / 4:3 (4:3 files have no bars), front and back camera, any phone orientation
- NightShot (visual emulation; the separate LIGHT button is the phone torch) and Super NightShot
- FADER (black / white), EXPOSURE, WHITE BAL., SPOT FOCUS / SPOT METER, SHARPNESS

**Firmware** (touch-panel UI in a pixel OSD font)
- STBY / REC, tape counter, remaining tape and battery, date/time on power-on, REC and
  NIGHTSHOT blinking like the real LCD
- P-MENU and the MENU drum with the camcorder's items; only working items are shown
- Virtual Mini DV cassette (DVM60: 60 min SP / 90 min LP), END SEARCH, eject / insert
- PLAY/EDIT: tape-order playback of your scenes with time code, transport keys and DATA CODE
  (date/time of the frame, or CAMERA DATA: exposure, white balance, shutter, F, gain — the
  phone's real values recorded with the scene)
- The OSD and the playback picture turn with the phone (with hysteresis, frozen while recording)
- Optional BURN OSD: the indicators burned into the recording, like a camcorder's display output

**Sound** (post-processing after STOP, video samples untouched, sync kept)
- AUDIO EMU: DV 12-bit 32 kHz / 16-bit 48 kHz (follows AUDIO MODE), LO-FI, RETRO (thin mic,
  pumping AGC, light 19 kHz bit-crush), CLEAN
- TAPE NOISE: quiet tape-transport loop under the recording; ORIG AUDIO KEEP saves the original
  next to a `_dv` copy
- UI sounds (key click / release, REC, warnings, cassette, power) — not mixed into clips: the
  phone microphone hears them, as the camcorder's did

**App settings** in the menu (not on the real camera): CCD LOOK (scanlines, interlace, noise,
smear, flare, vignette, colour), BEEP VOL, BURN OSD, AUDIO EMU, ORIG AUDIO, TAPE NOISE.

## Files

- Video: `Movies/Handycam/ccdcam_YYYYMMDD_HHMMSS.mp4` — H.264 + AAC MP4 from CameraX (HD,
  16:9 or 4:3), 25 fps, rotation written by the app. Not a DV file.
- Photos: `Pictures/Handycam/`.
- A clip is recorded into app-private storage, rotated and its sound processed there, and only
  then published to the gallery in one step. Clips left behind if the app is killed are
  published on next start.

## How it works

- **CameraX 1.4**: `Preview` + `VideoCapture<Recorder>` with two single-target `CameraEffect`s;
  `CcdSurfaceProcessor` (own EGL context, OES input) renders `assets/shaders/ccd.frag`, the
  interlace weave, the 25p frame decimator and, for video, the GL OSD burn-in.
- **Firmware**: `firmware/` — `FirmwareNavigator` (screens and menu state), `HC90eMenu`,
  `FirmwareOsdView` (touch panel), `Hc90eController` (camera / playback / cassette logic).
- **OSD**: `osd/` — one scene description drawn by Canvas on screen and by GL into the video,
  pixel font atlas with `GL_NEAREST`.
- **Audio**: `audio/` — AAC decode → `AudioChain` (mic EQ, hiss, AGC, limiter, resampler, DV
  quantisation, crusher) → AAC encode → `MediaMuxer` with copied video samples.

## Build

```
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleRelease      # release APK, signed with the bundled debug keystore
```

Gradle 8.9, JDK 17. CI builds and tests every push; tags `v*` publish a release APK.
Device checks before a release: [`docs/regression-checklist.md`](docs/regression-checklist.md).

## Credits

- [CCDCam](https://github.com/sturq/ccdcam) by sturq — the camera pipeline this fork builds on.
- OSD font: adapted from **PhoenixVGA 8x14**, *The Ultimate Oldschool PC Font Pack* by VileR
  ([int10h.org](https://int10h.org/oldschool-pc-fonts/)), CC BY-SA 4.0.
- UI and tape sounds: rendered for this project.

## License

[GPL-3.0-only](LICENSE). Sony, Handycam and NightShot are trademarks of Sony Group Corporation,
used here only to describe what is emulated.
