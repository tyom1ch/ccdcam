# HC90E firmware UI — references

What the HC90E PAL profile's on-screen interface is built from. Every element lists its
source, the camera model the source shows, and how confident the reproduction is.
**No Sony graphics, fonts, photos or sounds are stored in this repository**; elements are
redrawn in Android Canvas from these references.

Confidence levels:
- **Confirmed (HC90E)** — HC90E PAL documentation shows it.
- **Confirmed (HC90)** — the same-generation NTSC HC90 shows it (Japanese or US unit). Same
  firmware family, but language/NTSC details may differ.
- **Approximation** — no source found; chosen to be consistent with the confirmed elements.

## Sources

| ID | Source | Model | Used for |
|---|---|---|---|
| M-E | *DCR-HC90E Operating Guide* 2-548-308-11(1), © 2005 Sony — [Internet Archive `manualsbase-id-26109`](https://archive.org/details/manualsbase-id-26109) | **HC90E (PAL, English)** | layout of every screen, wording, menu tree, defaults, clock format, warnings |
| M-U | *DCR-HC90 Camera Operations Guide* 2-548-307-11(1) — [manualsdir](https://www.manualsdir.com/manuals/444304/sony-dcr-hc90.html) | HC90 (NTSC, US) | cross-check of wording |
| P-IT | ITmedia review "ソニー「DCR-HC90」", 17 Feb 2005 — [page 3](https://www.itmedia.co.jp/news/article/0502/17/1050217104/3) (image IDs 28025–28048) | HC90 (NTSC, Japanese UI) | **colours, bevels, fonts, proportions** of the real LCD; NightShot look |

Manual figures are black-and-white line art; all colours come from P-IT photos (sampled
from JPEGs, so ±10 per channel).

## Screens

### 1. CAMERA-TAPE recording screen — Confirmed (HC90E), M-E p. 26 "Indicators displayed during recording/playback"
| # | Element | Position (fraction of the 16:9 LCD) | Text / format | Confidence |
|---|---|---|---|---|
| 1 | Remaining battery | top-left, x 0.02–0.27, y 0.05–0.13 | battery icon + `60min` | layout Confirmed (HC90E); minutes derived from phone battery % = Approximation |
| 2 | Recording mode | top, x 0.32 | boxed `SP` / `LP` | Confirmed (HC90E) |
| 3 | Recording status | top centre-right, x 0.51–0.67 | `STBY` / `REC` | text Confirmed (HC90E); STBY **green #3BE638** Confirmed (HC90, P-IT 28035/28036); REC colour (red) Approximation |
| 4 | Tape counter / time code | top-right, x 0.72–0.99 | counter `0:00:00` (h:mm:ss), time code `0:00:00:00` (h:mm:ss:ff) | Confirmed (HC90E); hour not zero-padded |
| 5 | Remaining tape | under 4 | `60min` + cassette icon | Confirmed (HC90E); REMAINING AUTO shows it ~8 s (M-E p. 54) |
| 6 | END SEARCH / EDIT SEARCH / Rec review switch | bottom-right, x 0.60–0.79 | cassette icon button | Confirmed (HC90E) |
| 7 | Personal Menu button | bottom-right, x 0.80–0.99 | `P-MENU` | Confirmed (HC90E) |
| – | Date / time after power-on | date bottom-left, time bottom-right (P-IT 28035) | about 5 s after switching to CAMERA (M-U p. 13) | position Confirmed (HC90); HC90E format: 24-hour clock, Y-M-D entry (M-E p. 14). Date display order on PAL units: Approximation (`D M YYYY`, from DATA CODE placeholder `[-- -- ----]`, M-E p. 54) |

OSD text: white with a dark outline (P-IT). Font: **Arimo Bold** (SIL OFL 1.1, Helvetica-metric) —
Approximation of the OSD typeface; the manual's figures use a Helvetica-style face.

### 2. P-MENU — Confirmed (HC90E) layout, M-E p. 34/56; colours P-IT 28036
- 2 × 3 grid of buttons, left column: ⇑ (double chevron) button, page counter `1/3`, ⇓ button;
  close `✕` at top-right.
- CAMERA-TAPE page 1, **Confirmed**: `MENU`, `DISP GUIDE` / `TELE MACRO`, `EXPOSURE` /
  `SPOT FOCUS`, `FADER` (M-E p. 56 delete-screen figure; P-IT 28036 shows the same order).
- PLAY/EDIT page 1, **Confirmed (HC90E)**: `MENU`, `DISP GUIDE` / `END SEARCH`, `VOLUME` /
  `VAR. SPD PB`, `REC CTRL` (M-E p. 34).
- Pages 2–3 default contents: not documented → **Approximation**. `P-MENU SET UP` exists and
  cannot be deleted (M-E p. 56) → placed last.
- Button: fill **#ABE2DD**, 1 px light top/left edge, dark bottom/right edge, dark outer border,
  black bold label. `✕`: fill **#0238A3**, white cross. (P-IT 28036, zoomed.)
- Unavailable items are grayed out (M-E p. 34). Items not emulated yet use the same grayed state.

### 3. MENU (drum) — Confirmed (HC90E) layout, M-E p. 34/14; colours P-IT 28037
- Left: category icons on a rotating cylinder; centre: dark rounded drum (**#393A37**,
  semi-transparent), items above/below the selection shrink with perspective.
- Selected row: full-width bar **#ABE2DD**, black text; current value in a tag **#40E0D6** below
  the bar's right end.
- ▲ above / ▼ below the drum, `✕` top-right (blue), ↩ bottom-left, `OK` bottom-right.
- Categories (M-E p. 36–37): `CAMERA SET`, `MEMORY SET`, `PICT.APPLI.`, `EDIT/PLAY`,
  `STANDARD SET`, `TIME/LANGU.` with their items listed in `firmware/HC90eMenu.kt`.

### 4. Setting screen — Confirmed (HC90E), M-E p. 34 step ③–④
- Title line `ITEM: VALUE` (e.g. `REMOTE CTRL: ON`) under the indicator row, options as buttons,
  the selected option marked with a thick left edge, ↩ bottom-right; after a change `OK`.

### 5. PLAY/EDIT tape playback — Confirmed (HC90E) layout M-E p. 21; P-IT 28039
- Bottom row: `■` stop, `◀◀` rewind, `▶ II` play/pause, `▶▶` fast-forward, `P-MENU`.
- Top: `SP`, transport state, time code `0:06:26:21` (P-IT 28039).
- Playback stops automatically if paused for more than 3 minutes (M-E p. 21).

## Menu defaults — M-E p. 51–55 (first option listed = default)
`REC MODE` SP / LP (LP = 1.5 × SP time) · `AUDIO MODE` 12BIT / 16BIT · `DATA CODE` OFF /
DATE/TIME / CAMERA DATA · `REMAINING` AUTO (about 8 s) / ON · `BEEP` ON ("a melody sounds when
you start/stop recording, or operate the touch panel") / OFF · `MENU ROTATE` NORMAL / OPPOSITE ·
`A.SHUT OFF` 5 min / NEVER · `GUIDEFRAME` OFF / ON (frame not recorded).

## Tape — Confirmed (HC90E) unless noted
- Mini DV DVM60: SP 60 min, LP 90 min (spec page).
- Warnings (M-E p. 98–99): cassette indicator flashes slowly when less than 5 minutes remain,
  fast when the tape has run out; messages `Insert a cassette`, `The tape has reached the end.`
- END SEARCH: "END SEARCH EXECUTING" with CANCEL (M-U p. 31).

## Sounds — Approximation
No legally redistributable recordings of the HC90 "melody" were found. The beeps are
synthesized with `android.media.ToneGenerator`; the triggers (record start/stop, touch panel,
warnings) are Confirmed (HC90E, M-E p. 54 and p. 99).

## Corrections to earlier notes
- **Super NightShot is documented for the HC90E**: `SUPER NS (Super NightShot)` and `NS LIGHT`
  are in the CAMERA SET menu (M-E p. 36 and index). `docs/hc90e-reference.md` is updated.
