# Regression checklist

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
