#!/usr/bin/env python3
"""
Builds app/src/main/assets/osd/hc90e_osd_font.txt — the pixel font + pictograms of the
emulated DCR-HC90E OSD.

Glyph source: "PhoenixVGA 8x14" from The Ultimate Oldschool PC Font Pack v2.2 by VileR
(https://int10h.org/oldschool-pc-fonts/), CC BY-SA 4.0, as redistributed in
https://github.com/rotordeck/osd-fonts (fonts/pc/Bm437_PhoenixVGA_8x14.FON,
sha256 2a5da7ff83c530dcf2a9cab6e96a21cbe221ad6c2c6316f43f55d92236ca5608).

Adaptations (towards the real HC90 OSD, see docs/hc90e-ui-references.md "OSD font"):
the zero loses its slash, serifs are removed from the letters used by the indicators.
The adapted font is CC BY-SA 4.0 (ShareAlike); it is compatible with this GPL-3.0 app.
Pictograms (battery, cassette, SP/LP, transport) are original pixel art of this project.

Usage:
    python tools/build_osd_font.py Bm437_PhoenixVGA_8x14.FON app/src/main/assets/osd/hc90e_osd_font.txt
"""
import struct
import sys

H = 14  # glyph rows


def fnt_from_fon(fon: bytes) -> bytes:
    ne = struct.unpack_from('<I', fon, 0x3C)[0]
    assert fon[ne:ne + 2] == b'NE', 'not an NE executable'
    rtab = ne + struct.unpack_from('<H', fon, ne + 0x24)[0]
    shift = struct.unpack_from('<H', fon, rtab)[0]
    p = rtab + 2
    while True:
        tid = struct.unpack_from('<H', fon, p)[0]
        if tid == 0:
            break
        count = struct.unpack_from('<H', fon, p + 2)[0]
        p += 8
        for _ in range(count):
            off, length = struct.unpack_from('<HH', fon, p)
            if tid == 0x8008:  # RT_FONT
                return fon[off << shift:(off << shift) + (length << shift)]
            p += 12
    raise SystemExit('no RT_FONT resource')


def glyphs_from_fnt(data: bytes) -> dict:
    ver = struct.unpack_from('<H', data, 0)[0]
    assert ver == 0x200, f'unsupported FNT version {ver:#x}'
    assert struct.unpack_from('<H', data, 0x58)[0] == H
    first, last = data[0x5F], data[0x60]
    out = {}
    for i, ch in enumerate(range(first, last + 1)):
        w, boff = struct.unpack_from('<HH', data, 0x76 + i * 4)
        assert w == 8
        out[ch] = [''.join('#' if data[boff + y] >> (7 - x) & 1 else '.' for x in range(8))
                   for y in range(H)]
    return out


def g(*rows):
    rows = list(rows)
    assert len(rows) == H and all(len(r) == len(rows[0]) for r in rows), rows
    return rows


B = '........'
# Sans-serif adaptations (rows 0-1 and 11-13 are empty in this font).
PATCHES = {
    '0': g(B, B, '.#####..', '##...##.', '##...##.', '##...##.', '##...##.', '##...##.',
           '##...##.', '##...##.', '.#####..', B, B, B),
    '1': g(B, B, '...##...', '..###...', '.####...', '...##...', '...##...', '...##...',
           '...##...', '...##...', '...##...', B, B, B),
    '4': g(B, B, '....##..', '...###..', '..####..', '.##.##..', '##..##..', '##..##..',
           '#######.', '....##..', '....##..', B, B, B),
    'T': g(B, B, '.######.', '...##...', '...##...', '...##...', '...##...', '...##...',
           '...##...', '...##...', '...##...', B, B, B),
    'B': g(B, B, '.#####..', '.##..##.', '.##..##.', '.##..##.', '.#####..', '.##..##.',
           '.##..##.', '.##..##.', '.#####..', B, B, B),
    'Y': g(B, B, '.##..##.', '.##..##.', '.##..##.', '.##..##.', '..####..', '...##...',
           '...##...', '...##...', '...##...', B, B, B),
    'P': g(B, B, '.#####..', '.##..##.', '.##..##.', '.##..##.', '.#####..', '.##.....',
           '.##.....', '.##.....', '.##.....', B, B, B),
    'L': g(B, B, '.##.....', '.##.....', '.##.....', '.##.....', '.##.....', '.##.....',
           '.##.....', '.##.....', '.######.', B, B, B),
    'E': g(B, B, '.######.', '.##.....', '.##.....', '.##.....', '.#####..', '.##.....',
           '.##.....', '.##.....', '.######.', B, B, B),
    'R': g(B, B, '.#####..', '.##..##.', '.##..##.', '.##..##.', '.#####..', '.##.##..',
           '.##.##..', '.##..##.', '.##..##.', B, B, B),
    'i': g(B, B, '...##...', '...##...', B, '...##...', '...##...', '...##...',
           '...##...', '...##...', '...##...', B, B, B),
}

E16 = '................'


def battery(level: int):
    """Battery pictogram, terminal on the left, level 0..4 filled from the right."""
    fill = ''.join('#' if x >= 13 - 2 * level else '.' for x in range(5, 13))
    body = '.###.' + fill + '.#.'
    return g(E16, E16, '...############.', '...#..........#.', body, body, body, body,
             '...#..........#.', '...############.', E16, E16, E16, E16)


SPRITES = {
    **{f'battery_{n}': battery(n) for n in range(5)},
    'cassette': g(E16, E16, '..############..', '.#............#.', '.#.###....###.#.',
                  '.#.#.#....#.#.#.', '.#.###....###.#.', '.#....####....#.', '.#............#.',
                  '..############..', E16, E16, E16, E16),
    'sp': g(E16, '###############.', '#.............#.', '#...###.###...#.', '#...#...#.#...#.',
            '#...###.###...#.', '#.....#.#.....#.', '#...###.#.....#.', '#.............#.',
            '###############.', E16, E16, E16, E16),
    'lp': g(E16, '###############.', '#.............#.', '#...#...###...#.', '#...#...#.#...#.',
            '#...#...###...#.', '#...#...#.....#.', '#...###.#.....#.', '#.............#.',
            '###############.', E16, E16, E16, E16),
    # NightShot indicator: a crescent moon in a circle (approximation of the LCD icon).
    'nightshot': g(E16, '.....######.....', '...##......##...', '..#....###...#..', '.#....###.....#.',
                   '.#...###......#.', '.#...###......#.', '.#...###......#.', '.#....###.....#.',
                   '..#....###...#..', '...##......##...', '.....######.....', E16, E16),
    'play': g(B, B, '##......', '####....', '######..', '#######.', '######..', '####....',
              '##......', B, B, B, B, B),
    'pause': g(B, B, '##..##..', '##..##..', '##..##..', '##..##..', '##..##..', '##..##..',
               '##..##..', B, B, B, B, B),
    'stop': g(B, B, B, '######..', '######..', '######..', '######..', '######..', '######..',
              B, B, B, B, B),
}


def main() -> int:
    fon_path, out_path = sys.argv[1], sys.argv[2]
    glyphs = glyphs_from_fnt(fnt_from_fon(open(fon_path, 'rb').read()))
    lines = [
        '// DCR-HC90E emulator OSD font. Generated by tools/build_osd_font.py - do not edit.',
        '// Format: "glyph <hex code>" or "sprite <name>", then 14 rows; # = pixel on.',
        '// Glyphs: "PhoenixVGA 8x14", The Ultimate Oldschool PC Font Pack v2.2 by VileR',
        '//   (https://int10h.org/oldschool-pc-fonts/), CC BY-SA 4.0; adapted (unslashed zero,',
        '//   sans-serif indicator letters). This adapted font is licensed CC BY-SA 4.0.',
        '// Sprites: original pixel art of this project (GPL-3.0).',
        f'// patched: {"".join(sorted(PATCHES))}',
    ]
    for ch in range(0x20, 0x7F):
        rows = PATCHES.get(chr(ch), glyphs[ch])
        lines.append(f'glyph {ch:#04x}')
        lines += rows
    for name, rows in SPRITES.items():
        lines.append(f'sprite {name}')
        lines += rows
    open(out_path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
    print(f'wrote {out_path}: {0x7F - 0x20} glyphs, {len(SPRITES)} sprites')
    return 0


if __name__ == '__main__':
    sys.exit(main())
