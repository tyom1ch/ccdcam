# DCR-HC90E reference: documented vs approximated

Used by the HC90E PAL profile. Every item is marked as **documented** (with source) or
**approximation** (artistic emulation, no official source). Do not present approximations
as specifications.

## Sources

- **[S1]** Sony DCR-HC90 Camera Operations Guide 2-548-307-11(1), "Specifications", p. 115,
  and index p. 117 — <https://www.manualsdir.com/manuals/444304/sony-dcr-hc90.html?page=115>.
  This is the **NTSC (HC90)** manual.
- **[S2]** DCR-HC90E specification page, manualsonline —
  <https://powertool.manualsonline.com/manuals/mfg/sony/dcrhc90e.html?p=113>. Seen only as a
  search result summary ("PAL colour, CCIR standards"; PCM 12 bit 32 kHz / 16 bit 48 kHz).
  TODO: read the page itself and quote it.
- **[S3]** DCR-HC90E Operation Guide 2-548-308-11(1) —
  <https://www.manualowl.com/m/Sony/DCR-HC90E/Manual/270901?page=3>. Not read yet.
- **[DV]** DV format (IEC 61834) for 625/50 systems. General format knowledge, not an HC90E
  document.

## Documented

| Item | Value | Source |
|---|---|---|
| Video signal | PAL colour, CCIR standards | [S2] (to quote) |
| Media | Mini DV cassette; SP 60 min / LP 90 min with DVM60 | [S1] |
| Tape speed | SP approx. 18.81 mm/s, LP approx. 12.56 mm/s | [S1] |
| Audio | Rotary heads, PCM; 12 bits (Fs 32 kHz, stereo 1, stereo 2), 16 bits (Fs 48 kHz, stereo) | [S1], [S2] |
| Image device | 5.9 mm (1/3 type) CCD; gross approx. 3 310 000 px; effective (movie) approx. 2 050 000 px | [S1] |
| Lens | Carl Zeiss Vario-Sonnar T*, 10× optical, 120× digital, f = 5.1–51 mm, F1.8–2.9 | [S1] |
| 16:9 | "wide angle pixel read-out" (real wide mode, not a crop) | [S1] |
| White balance | AUTO, ONE PUSH, INDOOR (3 200 K), OUTDOOR (5 800 K) | [S1] |
| NightShot | 0 lx "during NightShot function", footnote: shooting with infrared lighting | [S1] |
| Features present | Color Slow Shutter, Digital effect, Digital zoom, Exposure, Back light, Auto shutter, Aspect ratio | [S1] index (pages only, settings not read) |
| LCD | 6.9 cm (2.7 type, 16:9), 123 200 dots (560 × 220) | [S1] |

## DV format (not HC90E-specific)

| Item | Value | Source |
|---|---|---|
| Active picture | 720 × 576 | [DV] |
| Frame/field rate | 25 frames / 50 interlaced fields per second | [DV] |
| Chroma sampling | 4:2:0 for 625/50 DV | [DV] |
| Video bitrate | approx. 25 Mbit/s, intra-frame DCT | [DV] |

## Approximations (no official source)

- Super NightShot: **not found** in [S1]. Treated as an artistic mode.
- NightShot look (monochrome green tint, noise). In the app the phone torch is a separate
  "LIGHT" control and is never described as infrared.
- CCD noise, smear, flare, colour grade, DCT blocking, interlace combing.
- OSD look, font, P-MENU layout, button sounds (drawn/generated from scratch; no Sony assets).
- AGC breathing, mic EQ, preamp noise, soft clipping in the audio emulator.

## Not emulated in the output file

The app writes MP4 (H.264 + AAC) through CameraX `Recorder`. It does not produce DV25
(.dv/.avi), does not store 50i fields, and does not write a 720 × 576 file.
