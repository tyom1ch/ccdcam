#!/usr/bin/env python3
"""
Imports the project's own UI sound renders into app/src/main/res/raw as PCM 16-bit mono 48 kHz.

    python tools/import_ui_sounds.py <folder with the source .wav files>

- UI sounds: mono, leading silence trimmed (2 ms kept), peak-normalised to -3 dBFS,
  3 ms fade in/out (no clicks).
- Tape loop: mono, seamless loop (30 ms crossfade of the tail into the head), RMS-normalised
  to -30 dBFS (peak limited to -1 dBFS); the mix level is applied in the app (SfxMixer).
"""
import math
import struct
import sys
from pathlib import Path

RATE = 48_000
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"

# source file -> Android resource name
UI = {
    "click.wav": "ui_click",
    "decklick.wav": "ui_declick",
    "alert.wav": "alert",
    "power_on.wav": "power_on",
    "cassete_eject.wav": "cassette_eject",
    "cassete_inject.wav": "cassette_insert",
}
LOOP = {"camera_rec_loop.wav": "tape_loop"}


def read_mono(path: Path) -> list:
    d = path.read_bytes()
    assert d[:4] == b"RIFF" and d[8:12] == b"WAVE", path
    p, fmt, raw = 12, None, None
    while p + 8 <= len(d):
        cid = d[p:p + 4]
        size = struct.unpack("<I", d[p + 4:p + 8])[0]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", d[p + 8:p + 24])
        elif cid == b"data":
            raw = d[p + 8:p + 8 + size]
        p += 8 + size + (size & 1)
    tag, ch, rate, _, _, bits = fmt
    assert tag == 1 and bits == 16 and rate == RATE, f"{path}: need PCM16 {RATE} Hz"
    s = struct.unpack("<%dh" % (len(raw) // 2), raw)
    return [sum(s[i:i + ch]) / ch / 32768.0 for i in range(0, len(s) - ch + 1, ch)]


def write(path: Path, x: list) -> None:
    pcm = b"".join(struct.pack("<h", max(-32768, min(32767, round(v * 32767)))) for v in x)
    hdr = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    hdr += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
    hdr += b"data" + struct.pack("<I", len(pcm))
    path.write_bytes(hdr + pcm)


def db(x: float) -> float:
    return 10 ** (x / 20)


def ui(x: list) -> list:
    pk = max(abs(v) for v in x)
    start = next(i for i, v in enumerate(x) if abs(v) > pk * 0.02)
    x = x[max(0, start - RATE * 2 // 1000):]
    g = db(-3) / pk
    x = [v * g for v in x]
    f = RATE * 3 // 1000
    for i in range(f):
        x[i] *= i / f
        x[-1 - i] *= i / f
    return x


def loop(x: list) -> list:
    n = RATE * 30 // 1000
    body = x[:len(x) - n]
    for i in range(n):                       # crossfade the tail into the head
        t = i / n
        body[i] = body[i] * t + x[len(x) - n + i] * (1 - t)
    rms = math.sqrt(sum(v * v for v in body) / len(body))
    g = min(db(-30) / rms, db(-1) / max(abs(v) for v in body))
    return [v * g for v in body]


def main() -> int:
    src = Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    for name, res in UI.items():
        write(OUT / f"{res}.wav", ui(read_mono(src / name)))
        print("ui  ", name, "->", res)
    for name, res in LOOP.items():
        write(OUT / f"{res}.wav", loop(read_mono(src / name)))
        print("loop", name, "->", res)
    return 0


if __name__ == "__main__":
    sys.exit(main())
