"""Builds Erebus Sans, the client's UI font, from Geist (SIL Open Font License 1.1).

Erebus Sans is a renamed, modified derivative of Geist:
  * subset to Latin, punctuation, arrows and the symbols the client uses
  * tabular (fixed-width) digits by default, so changing numbers on the HUD don't jitter
  * a set of UI icon glyphs in the Private Use Area (U+E000 onward), drawn below
  * renamed to "Erebus Sans" as the OFL asks of modified versions

Usage:
    pip install fonttools
    python tools/build_font.py <path to geist-sans folder> src/main/resources/erebus/font

The Geist TTFs come from the `geist` npm package (dist/fonts/geist-sans).
"""

import math
import sys
from pathlib import Path

from fontTools import subset
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

# Source weight -> output style. Medium reads better than Regular at small UI sizes on dark backgrounds.
STYLES = {"Regular": "Geist-Medium.ttf", "Bold": "Geist-SemiBold.ttf"}

UNICODES = (
    list(range(0x20, 0x7F))        # Basic Latin
    + list(range(0xA0, 0x180))     # Latin-1 Supplement, Latin Extended-A
    + list(range(0x2010, 0x205F))  # general punctuation (dashes, quotes, bullet, ellipsis)
    + list(range(0x2190, 0x2200))  # arrows
    + [0x20AC, 0x2122, 0x2212, 0x2264, 0x2265, 0x2248, 0x00D7, 0x221E]
)

ICON_ADVANCE = 760


# ---------------------------------------------------------------------------------------------------- shapes
# Coordinates are font units (1000 per em), y up, baseline at 0, icons sit between y=0 and y=700.

def _area(pts):
    return sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1] for i in range(len(pts))) / 2


def polygon(pen, pts, clockwise=True):
    """A closed straight-edged contour. TrueType fills clockwise outer contours; holes run counter-clockwise."""
    pts = [(round(x), round(y)) for x, y in pts]
    if (_area(pts) < 0) != clockwise:
        pts = pts[::-1]
    pen.moveTo(pts[0])
    for p in pts[1:]:
        pen.lineTo(p)
    pen.closePath()


def ellipse(pen, cx, cy, rx, ry, clockwise=True, segments=12):
    """An ellipse from quadratic arcs: on-curve points on the ellipse, off-curve points at the tangent intersections."""
    step = 2 * math.pi / segments
    k = 1 / math.cos(step / 2)
    angles = [i * step for i in range(segments)]
    if clockwise:
        angles = [-a for a in angles]
        step = -step
    on = [(cx + rx * math.cos(a), cy + ry * math.sin(a)) for a in angles]
    off = [(cx + rx * k * math.cos(a + step / 2), cy + ry * k * math.sin(a + step / 2)) for a in angles]
    pen.moveTo((round(on[0][0]), round(on[0][1])))
    for i in range(segments):
        nxt = on[(i + 1) % segments]
        pen.qCurveTo((round(off[i][0]), round(off[i][1])), (round(nxt[0]), round(nxt[1])))
    pen.closePath()


def disc(pen, cx, cy, r):
    ellipse(pen, cx, cy, r, r)


def ring(pen, cx, cy, r, w, ry=None):
    ry = r if ry is None else ry
    ellipse(pen, cx, cy, r + w / 2, ry + w / 2, clockwise=True)
    ellipse(pen, cx, cy, r - w / 2, ry - w / 2, clockwise=False)


def stroke(pen, x1, y1, x2, y2, w, cap=True):
    """A straight stroke of width w; with cap, its ends are extended by w/2 for clean joins."""
    dx, dy = x2 - x1, y2 - y1
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy * w / 2, ux * w / 2
    e = w / 2 if cap else 0
    a = (x1 - ux * e, y1 - uy * e)
    b = (x2 + ux * e, y2 + uy * e)
    polygon(pen, [(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny), (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)])


def polyline(pen, pts, w):
    for i in range(len(pts) - 1):
        stroke(pen, *pts[i], *pts[i + 1], w)


def rect(pen, x, y, w, h):
    polygon(pen, [(x, y), (x, y + h), (x + w, y + h), (x + w, y)])


def rounded(pen, x, y, w, h, r):
    """Clockwise rounded rectangle."""
    r = min(r, w / 2, h / 2)
    pen.moveTo((round(x + r), round(y)))
    pen.qCurveTo((round(x), round(y)), (round(x), round(y + r)))
    pen.lineTo((round(x), round(y + h - r)))
    pen.qCurveTo((round(x), round(y + h)), (round(x + r), round(y + h)))
    pen.lineTo((round(x + w - r), round(y + h)))
    pen.qCurveTo((round(x + w), round(y + h)), (round(x + w), round(y + h - r)))
    pen.lineTo((round(x + w), round(y + r)))
    pen.qCurveTo((round(x + w), round(y)), (round(x + w - r), round(y)))
    pen.closePath()


# ---------------------------------------------------------------------------------------------------- icons

C = 380   # horizontal center
M = 330   # vertical center
W = 78    # stroke width


def icon_search(p):
    ring(p, 320, 400, 215, W)
    stroke(p, 480, 240, 660, 60, 96)


def icon_close(p):
    stroke(p, 150, 100, 610, 560, W)
    stroke(p, 150, 560, 610, 100, W)


def icon_chevron_right(p):
    polyline(p, [(260, 620), (520, 330), (260, 40)], W)


def icon_chevron_left(p):
    polyline(p, [(500, 620), (240, 330), (500, 40)], W)


def icon_chevron_down(p):
    polyline(p, [(90, 470), (380, 190), (670, 470)], W)


def icon_chevron_up(p):
    polyline(p, [(90, 190), (380, 470), (670, 190)], W)


def icon_check(p):
    polyline(p, [(90, 330), (290, 130), (670, 560)], 90)


def icon_sliders(p):
    for y, kx in ((600, 500), (330, 230), (60, 440)):
        stroke(p, 60, y, 700, y, 62, cap=False)
        disc(p, kx, y, 92)


def icon_pin(p):
    disc(p, C, 450, 250)
    polygon(p, [(160, 330), (600, 330), (C, -10)])
    ellipse(p, C, 450, 100, 100, clockwise=False)


def icon_crosshair(p):
    ring(p, C, M, 240, 70)
    for x1, y1, x2, y2 in ((C, -10, C, 170), (C, 490, C, 670), (40, M, 210, M), (550, M, 720, M)):
        stroke(p, x1, y1, x2, y2, 70, cap=False)
    disc(p, C, M, 55)


def icon_movement(p):
    polyline(p, [(110, 600), (340, 330), (110, 60)], W)
    polyline(p, [(400, 600), (630, 330), (400, 60)], W)


def icon_eye(p):
    ring(p, C, M, 320, 70, ry=190)
    disc(p, C, M, 115)


def icon_player(p):
    disc(p, C, 520, 150)
    rounded(p, 110, 0, 540, 300, 150)


def icon_globe(p):
    ring(p, C, M, 300, 64)
    ring(p, C, M, 120, 58, ry=300)
    stroke(p, 90, M, 670, M, 58, cap=False)


def icon_radar(p):
    ring(p, C, M, 300, 60)
    ring(p, C, M, 165, 56)
    disc(p, C, M, 58)
    stroke(p, C, M, 590, 540, 60)


def icon_dots(p):
    for x in (110, C, 650):
        disc(p, x, M, 82)


def icon_grid(p):
    for x, y in ((60, 380), (420, 380), (60, 20), (420, 20)):
        rounded(p, x, y, 280, 280, 60)


def icon_drop(p):
    disc(p, C, 230, 240)
    polygon(p, [(150, 300), (610, 300), (C, 700)])


def icon_logo(p):
    rect(p, 80, 0, 150, 700)
    rect(p, 80, 0, 600, 130)
    rect(p, 80, 285, 470, 130)
    rect(p, 80, 570, 600, 130)


def icon_plus(p):
    stroke(p, C, 40, C, 620, 86, cap=False)
    stroke(p, 90, M, 670, M, 86, cap=False)


def icon_flag(p):
    stroke(p, 170, -20, 170, 700, 70, cap=False)
    polygon(p, [(170, 700), (640, 560), (170, 410)])


def icon_info(p):
    disc(p, C, 620, 65)
    rect(p, 330, 0, 100, 460)


def icon_map(p):
    polygon(p, [(40, 80), (40, 640), (260, 700), (260, 140)])
    polygon(p, [(300, 140), (300, 700), (460, 640), (460, 80)])
    polygon(p, [(500, 80), (500, 640), (720, 700), (720, 140)])


def icon_trash(p):
    rect(p, 60, 560, 640, 80)
    rect(p, 280, 620, 200, 70)
    polygon(p, [(130, 520), (630, 520), (590, 0), (170, 0)])


def icon_copy(p):
    rounded(p, 220, 0, 480, 520, 70)
    rounded(p, 60, 180, 120, 520, 40)
    rounded(p, 60, 580, 480, 120, 40)


def icon_target(p):
    ring(p, C, M, 290, 60)
    ring(p, C, M, 160, 60)
    disc(p, C, M, 60)


ICONS = [
    (0xE000, "search", icon_search),
    (0xE001, "close", icon_close),
    (0xE002, "chevron_right", icon_chevron_right),
    (0xE003, "chevron_down", icon_chevron_down),
    (0xE004, "check", icon_check),
    (0xE005, "sliders", icon_sliders),
    (0xE006, "pin", icon_pin),
    (0xE007, "crosshair", icon_crosshair),
    (0xE008, "movement", icon_movement),
    (0xE009, "eye", icon_eye),
    (0xE00A, "player", icon_player),
    (0xE00B, "globe", icon_globe),
    (0xE00C, "radar", icon_radar),
    (0xE00D, "dots", icon_dots),
    (0xE00E, "grid", icon_grid),
    (0xE00F, "drop", icon_drop),
    (0xE010, "logo", icon_logo),
    (0xE011, "plus", icon_plus),
    (0xE012, "flag", icon_flag),
    (0xE013, "info", icon_info),
    (0xE014, "chevron_left", icon_chevron_left),
    (0xE015, "chevron_up", icon_chevron_up),
    (0xE016, "map", icon_map),
    (0xE017, "trash", icon_trash),
    (0xE018, "copy", icon_copy),
    (0xE019, "target", icon_target),
]


# ---------------------------------------------------------------------------------------------------- build

def build(src: Path, out: Path, style: str):
    font = TTFont(src)

    # tabular digits by default: point the digit code points at the .tf glyphs
    cmap = font.getBestCmap()
    names = set(font.getGlyphOrder())
    digit_names = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
    for table in font["cmap"].tables:
        if not table.isUnicode():
            continue
        for i, name in enumerate(digit_names):
            if name + ".tf" in names and (0x30 + i) in table.cmap:
                table.cmap[0x30 + i] = name + ".tf"

    opts = subset.Options()
    opts.layout_features = ["kern", "liga", "calt", "ccmp", "locl", "case"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=UNICODES)
    sub.subset(font)

    glyf = font["glyf"]
    hmtx = font["hmtx"]
    order = list(font.getGlyphOrder())
    for code, name, draw in ICONS:
        gname = "icon_" + name
        pen = TTGlyphPen(None)
        draw(pen)
        glyph = pen.glyph()
        glyf.glyphs[gname] = glyph
        order.append(gname)
        glyph.recalcBounds(glyf)
        hmtx.metrics[gname] = (ICON_ADVANCE, getattr(glyph, "xMin", 0))
        for table in font["cmap"].tables:
            if table.isUnicode():
                table.cmap[code] = gname
    font.setGlyphOrder(order)
    glyf.glyphOrder = order
    font["maxp"].numGlyphs = len(order)
    if "post" in font:
        font["post"].formatType = 2.0
        font["post"].extraNames = []
        font["post"].mapping = {}

    family = "Erebus Sans"
    ps = "ErebusSans-" + style
    name = font["name"]
    for rec in list(name.names):
        if rec.nameID in (16, 17, 21, 22, 25):
            name.removeNames(nameID=rec.nameID)
    for nid, value in {
        1: family,
        2: style,
        3: f"{ps};erebus",
        4: f"{family} {style}",
        6: ps,
        5: "Version 1.000; derived from Geist",
        10: "Erebus Sans: UI font for the Erebus client, derived from Geist by Vercel. "
            "Subset, tabular figures by default, added UI icons in the Private Use Area.",
    }.items():
        name.setName(value, nid, 3, 1, 0x409)
        name.setName(value, nid, 1, 0, 0)

    out.mkdir(parents=True, exist_ok=True)
    path = out / f"{ps}.ttf"
    font.save(path)
    print(f"wrote {path} ({path.stat().st_size // 1024} KB, {len(order)} glyphs)")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    for style, file in STYLES.items():
        build(src_dir / file, out_dir, style)


if __name__ == "__main__":
    main()
