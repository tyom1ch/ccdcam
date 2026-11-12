# Regression checklist

> Since stage 5 the app has a single camera (HC90E PAL); the CCDCam profile was removed at the
> owner's request. Items below that mention `CCDCAM_DEFAULT` / CCDCam are historical.

Run on a real device at the end of every stage, for **every** camera profile that exists at
that stage (from stage 1 on: `CCDCAM_DEFAULT` and `HC90E_PAL`). A stage is not done until
GitHub Actions is green, the `ccdcam-debug` APK artifact was installed from that run, and
every item below passes.

Record the device model, Android version, APK commit hash and the result of each item in
the PR description.

## 1. Preview
- [ ] App starts, permission prompt appears on first launch (Android 9 and older: also storage).
- [ ] Filtered preview is visible with the back camera.
- [ ] Flip to the front camera and back works; flipping is refused while recording.
- [ ] Pinch zoom works and the zoom label updates.
- [ ] 16:9 / 4:3 toggle rebinds the camera without crash.

## 2. Video recording and saving
- [ ] 30 s recording: file appears in `Movies/CCDCam`, plays in the system gallery.
- [ ] 5 min recording: file plays to the end, no freeze, no audio dropouts.
- [ ] Pressing Home during recording finalizes the file (it still plays).
- [ ] Timecode in the HUD advances, REC dot blinks, state returns to `STBY` after stop.

## 3. File properties (`ffprobe`)
```
ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,r_frame_rate,avg_frame_rate,sample_rate,channels,duration -of compact FILE.mp4
ffprobe -v error -select_streams v:0 -show_entries frame=pkt_pts_time -of csv=p=0 FILE.mp4 | head -50
```
- [ ] One video stream (h264) and one audio stream (aac).
- [ ] Width/height and frame rate are what the stage expects (record the numbers).
- [ ] Video and audio durations differ by less than one frame.
- [ ] Video PTS are monotonic; note the PTS deltas (needed for the 25 fps stage).

## 4. A/V sync
- [ ] Clap test: clap in front of the camera at start and end of a 5 min clip; the clap
      sound and the hands meeting are on the same frame (± 1 frame) at both ends.

## 5. Orientation
- [ ] Record in portrait: the recorded video plays upright.
- [ ] Record in landscape (both directions): the recorded video plays upright.
- [ ] Note the direction of scanlines and the anamorphic stretch in preview and in the
      recorded file, in portrait and landscape (screenshots in the PR). Baseline for stage 2.

## 6. Photo
- [ ] Photo mode saves a JPEG to `Pictures/CCDCam`.
- [ ] Photo taken in portrait and in landscape opens upright.

## 7. Profile-specific (stage 1 onward)
- [ ] `CCDCAM_DEFAULT` looks identical to the previous release (side-by-side screenshot of
      the same scene, plus `python tools/calibrate.py before.mp4 after.mp4`).

## 8. Effect space (stage 2 onward)
Switch profile: long-press the bottom-left HUD label (`CCD CAM` / `HC90E PAL`). Switching is
refused while recording; the choice survives an app restart.
- [ ] `CCDCAM_DEFAULT` (`CCD CAM`): scanlines, grain size, vignette and colour identical to
      stage 1 in preview, in the recorded MP4 and in photos (portrait and landscape).
- [ ] `HC90E_PAL`: preview and recording work; note the scanline direction in preview and in
      the MP4 for portrait and landscape recording (screenshots in the PR). Expected: lines follow
      the output frame instead of the camera sensor.
- [ ] Switching back and forth during preview does not freeze or black out the preview.

## 9. HC90E PAL geometry (stage 3 onward)
The file is still CameraX `Recorder` output, 16:9 H.264 — not 720×576. 720×576 is only the
emulated sampling grid. Expected size: **1280×720 when recorded in landscape, 720×1280 in
portrait**. On Pixel 9 Pro XL CameraX's stream-sharing node rotates the video pixels (Recorder
logs `TransformationInfo ... getRotationDegrees=0`), so there is no rotation metadata.

Diagnostics: `adb logcat -s CcdProcessor` prints the processor buffer size, its
`sensorToBuffer` rotation (expected 0 with StreamSharing) and, for every framing change,
`uprightCw` (back camera on Pixel: 90 in portrait, 0 or 180 in landscape).

For each cell, check: scanlines are **horizontal relative to the upright image**; circles stay
round (no stretch); 4:3 shows black bars (pillarbox in landscape, letterbox in portrait);
16:9 fills the 16:9 frame.

| Aspect | Orientation | Preview | Recorded MP4 (played in Photos) |
|---|---|---|---|
| 16:9 | portrait  | [ ] | [ ] |
| 16:9 | landscape | [ ] | [ ] |
| 4:3  | portrait  | [ ] | [ ] |
| 4:3  | landscape | [ ] | [ ] |

- [ ] Rotate the phone **during** a recording: the effects in the preview stay locked to the
      orientation the recording started with (VideoCapture also ignores rotation changes
      mid-recording); after stop they follow the phone again.
- [ ] Front camera: one portrait + one landscape clip, scanlines horizontal in the MP4.
- [ ] `CCDCAM_DEFAULT`: identical to stage 2, including its original quirks (anamorphic
      squish 0.78, 4:3 rebinding preview only, sensor-aligned scanlines).

## 10. HC90E firmware UI (stage 4 onward)
References and confidence per element: `docs/hc90e-ui-references.md`. The firmware display is
phone UI only — it must **not** appear in recorded MP4s or photos (the recordable OSD is a
later, separate stage).

- [ ] CCDCam profile: no firmware display, HUD/brackets/zoom/date exactly as before, mode row
      shows PHOTO / VIDEO only.
- [ ] HC90E: indicator row (battery `NNmin`, `SP`, green `STBY`, counter `0:00:00`), remaining
      `60min` + cassette for ~8 s after switching on, date/time for ~5 s, END SEARCH and
      `P-MENU` buttons bottom-right inside the 16:9 LCD band.
- [ ] `P-MENU`: page `1/3` = MENU, DISP GUIDE / TELE MACRO, EXPOSURE / SPOT FOCUS, FADER;
      ⇑/⇓ cycle pages; unavailable items grayed; ✕ closes.
- [ ] `MENU`: category drum → STANDARD SET → REC MODE → LP → OK: indicator shows `LP`,
      remaining tape shows `90min` on a fresh tape. ↩ discards. BEEP OFF silences beeps.
- [ ] START: rising beep, `REC` (red), counter runs; STOP: falling beep, counter keeps the
      total; the MP4 is saved and A/V sync is unchanged (section 4).
- [ ] Long-press `TAPE`: cassette ejected → `Insert a cassette`, START refused with warning
      beep; long-press again inserts a fresh DVM60.
- [ ] `WIDE 16:9/4:3` (below the LCD) switches the picture without rebinding.
- [ ] `PLAY/EDIT`: camera off, recorded scenes play as one tape: ▶ II, ■, ◀◀ (scene start /
      previous), ▶▶ (next), time code `h:mm:ss:ff` (25 fps). DATA CODE DATE/TIME shows the
      scene's date/time. Back to `TAPE`: camera on, recording works.
- [ ] Pinch-zoom still works outside the touch-panel buttons.

## 11. Rotation of the firmware display (stage 5 onward)
The activity stays portrait (no recreation, so rotating never interrupts a recording or
rebinds CameraX); the firmware display and the body-control labels rotate with the phone.
- [ ] Landscape (both directions): the 16:9 LCD fills the preview, OSD text upright, buttons
      work where they are drawn.
- [ ] Upside-down portrait: OSD upright.
- [ ] Rotate during recording: recording continues, OSD follows the phone, the recorded
      picture keeps the orientation it started with (section 9).
- [ ] MEMORY / TAPE / PLAY/EDIT and WIDE labels rotate too.
