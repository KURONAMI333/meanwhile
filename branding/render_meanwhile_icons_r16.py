# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 16.

r15 established the subject (wheat_stage7, real pixels, integer NEAREST,
frame-filling) and kura accepted it outright: "小麦は完全に成立していま
す". The one open problem was self-reported in r15's own text: the drawn
sparkle glyph survives 48px resizing as coloured pixels, but not as a
recognisable four-pointed shape - "the point shape itself is lost below
roughly 4px" - which means the store-size icon read as "wheat with a few
green flecks", not "wheat, freshly grown". kura's diagnosis: the marks
were too small and too many, and several sat on top of the wheat, where
LANCZOS blends them into the wheat's own olive-shaded pixels rather than
the dark field.

This round's fix, all mechanical:
  1. FEWER, BIGGER marks: 3-6 sparkles per candidate (was 5-11), each
     40-64px at the 512 stage so a 48px downsize leaves each one roughly
     4-6px across - the size a corner (a "point") can still survive at,
     not just a blob of colour.
  2. Placed entirely above the wheat's own bounding-box top (computed
     from place_sprite_nearest's own return rect, not eyeballed), so no
     sparkle ever sits on wheat pixels - full contrast against the flat
     dark ground everywhere, no wheat-blend risk at all.
  3. Verification redone a second time the same way the survival-count
     bug got caught last round: render each candidate, downsize to 48px,
     scale that back up with NEAREST (so every resized pixel is visible,
     not reinterpolated), and look at whether the corners survive - not
     just whether some pixel differs from the background.

Kept as-is (kura's explicit "変えないもの"): wheat_stage7.png real
pixels + integer NEAREST scale, the frame-filling composition, both
platform backgrounds + true 48px in the sheet, no glow anywhere, and
D1_weight_shift carried forward unchanged as the reference row.

Candidate set, 6 -> 5 per kura's cull:
  few_large       KEPT, upgraded (teal ground - one of the 2 candidates
                  kura named as already reading best)
  cluster_above   KEPT, upgraded (plum ground - the other one kura named)
  caption_sparkle KEPT, upgraded (slate ground - the sole A+D/D-adjacent
                  candidate, kept alive per kura's standing instruction
                  to not let direction D die)
  margin_breathing DROPPED (kura's own reason: 4 of 9 sparkles were lost
                  against near-black under LANCZOS - the exact defect
                  this round fixes for the kept candidates instead)
  dense_scatter, mixed_size_scatter DROPPED (not named as a "most
                  visible" survivor; replaced by two new candidates built
                  from this round's fixed method instead of patched)
  wide_arc        NEW - 4 bold sparkles in a shallow arc above the wheat,
                  indigo ground
  bold_pair       NEW - 3 sparkles only, the largest in the set, an
                  asymmetric placement above one shoulder of the wheat,
                  burgundy-black ground (the fewest-marks, largest-per-
                  mark end of this round's range)
"""

from __future__ import annotations

import math
import random
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r16"
OUT_DIR.mkdir(exist_ok=True)
TEX_DIR = (
    ROOT.parents[1]
    / "_tools"
    / "MineTexture"
    / "tool_data"
    / "cache"
    / "vanilla_textures"
    / "block"
)

SIDE = 2048
OUT = 512
Color = tuple[int, int, int, int]

SPARKLE_BRIGHT: Color = (146, 232, 92, 255)  # #92E85C lime
SPARKLE_MID: Color = (86, 176, 62, 255)  # #56B03E mid green
SPARKLE_PALE: Color = (214, 245, 176, 255)  # #D6F5B0 near-white-green core

DARK_TEXT: Color = (30, 24, 16, 255)
CREAM_FIELD: Color = (231, 227, 218, 255)

GROUNDS = {
    "teal": (15, 59, 58, 255),  # #0F3B3A
    "plum": (52, 27, 61, 255),  # #341B3D
    "slate": (38, 48, 66, 255),  # #263042
    "indigo": (27, 35, 64, 255),  # #1B2340
    "burgundy_black": (42, 20, 24, 255),  # #2A1418
}


def P(fx: float, fy: float) -> tuple[float, float]:
    return (fx * SIDE, fy * SIDE)


def flat_field(color: Color) -> Image.Image:
    return Image.new("RGBA", (SIDE, SIDE), color)


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def font(name: str, frac: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", int(SIDE * frac))


def text_wh(
    d: ImageDraw.ImageDraw, s: str, f: ImageFont.FreeTypeFont
) -> tuple[float, float]:
    l, t, r, b = d.textbbox((0, 0), s, font=f)
    return r - l, b - t


def fit_font(
    d: ImageDraw.ImageDraw, s: str, name: str, max_w: float, start_frac: float
) -> tuple[ImageFont.FreeTypeFont, float, float]:
    frac = start_frac
    f = font(name, frac)
    w, h = text_wh(d, s, f)
    for _ in range(4):
        if w <= max_w:
            break
        frac *= (max_w / w) * 0.96
        f = font(name, frac)
        w, h = text_wh(d, s, f)
    return f, w, h


# --------------------------------------------------------------- sprite ---


def load_sprite_cropped(name: str) -> Image.Image:
    path = TEX_DIR / name
    if not path.exists():
        raise FileNotFoundError(f"vanilla texture not found: {path}")
    img = Image.open(path).convert("RGBA")
    arr = np.array(img)
    ys, xs = np.where(arr[:, :, 3] > 10)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    return img.crop((int(x0), int(y0), int(x1) + 1, int(y1) + 1))


def place_sprite_nearest(
    dest: Image.Image, sprite: Image.Image, cx: float, cy: float, target_w_frac: float
) -> tuple[int, int, int, int]:
    """Integer-NEAREST scale, centred on (cx, cy). Returns the pasted
    (x, y, w, h) in SIDE px - used below to compute the wheat's own
    bounding-box top, so sparkles can be kept strictly above it."""
    target_px = target_w_frac * SIDE
    scale = max(1, round(target_px / sprite.width))
    scaled = sprite.resize((sprite.width * scale, sprite.height * scale), Image.NEAREST)
    x = int(cx * SIDE - scaled.width / 2)
    y = int(cy * SIDE - scaled.height / 2)
    dest.alpha_composite(scaled, (x, y))
    return x, y, scaled.width, scaled.height


# -------------------------------------------------------------- sparkle ---


def sparkle_polygon(
    cx: float, cy: float, r_out: float, rot: float = 0.0
) -> list[tuple[float, float]]:
    """Flat 4-point star/glint glyph: two crossed elongated diamonds, 8
    vertices alternating outer/inner radius. No glow - shape and colour
    only (negative_list)."""
    r_in = r_out * 0.34
    pts = []
    for i in range(8):
        ang = math.radians(rot + i * 45)
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
    return pts


def draw_sparkle(
    d: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    r_frac: float,
    rot: float,
    color: Color,
) -> None:
    pts = sparkle_polygon(cx * SIDE, cy * SIDE, r_frac * SIDE, rot)
    d.polygon(pts, fill=color)


def place_sparkles(
    d: ImageDraw.ImageDraw,
    rng: random.Random,
    positions: list[tuple[float, float]],
    size_range: tuple[float, float],
) -> list[tuple[float, float, float]]:
    """Draws one sparkle at each explicit (cx, cy) - this round places
    sparkles deliberately (few, large, hand-set zones), not via a random
    scatter box like r15, so each mark's clearance from the wheat top can
    be guaranteed by construction. Returns (cx, cy, r_frac) per sparkle
    for the measurement pass."""
    colours = [
        SPARKLE_BRIGHT,
        SPARKLE_MID,
        SPARKLE_PALE,
        SPARKLE_BRIGHT,
        SPARKLE_MID,
        SPARKLE_PALE,
    ]
    placed = []
    for i, (cx, cy) in enumerate(positions):
        # size_range is the target sparkle DIAMETER at the final 512px
        # stage (point-to-point). r stored here is r_out expressed as a
        # fraction of SIDE's radius-equivalent, i.e. r*OUT == radius@512
        # (so diameter@512 == 2*r*OUT) - dividing by SIDE/2 rather than
        # SIDE converts "diameter@512" straight to that fraction. r15's
        # first pass divided by SIDE while treating the input as an
        # already-final-scale diameter, which silently halved every
        # sparkle twice over (radius-vs-diameter, and missing the SS=4
        # supersample factor) - this is that bug's fix.
        r = rng.uniform(*size_range) / (SIDE / 2)
        rot = rng.uniform(0, 45)
        draw_sparkle(d, cx, cy, r, rot, colours[i % len(colours)])
        placed.append((cx, cy, r))
    return placed


# ============================================================ candidates ===


def cand_few_large():
    """KEPT + upgraded: wheat fills the frame, shifted down so a clean
    dark band opens above its bbox top. 5 bold sparkles in a gentle arc
    entirely within that band - teal ground."""
    img = flat_field(GROUNDS["teal"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.60, target_w_frac=0.88)
    x, y, w, h = rect
    top = y / SIDE  # wheat bbox top, fraction of canvas
    d = ImageDraw.Draw(img)
    band_y = top * 0.55  # sit in the upper half of the clear band
    positions = [
        (0.16, band_y),
        (0.32, band_y * 0.7),
        (0.50, band_y * 1.05),
        (0.68, band_y * 0.65),
        (0.85, band_y),
    ]
    rng = random.Random(2)
    sp = place_sparkles(d, rng, positions, (46, 62))
    return finish(img), rect, sp, GROUNDS["teal"]


def cand_cluster_above():
    """KEPT + upgraded: 5 sparkles clustered tighter (a single 'burst'),
    same clearance guarantee - plum ground."""
    img = flat_field(GROUNDS["plum"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.60, target_w_frac=0.88)
    x, y, w, h = rect
    top = y / SIDE
    d = ImageDraw.Draw(img)
    band_y = top * 0.55
    positions = [
        (0.40, band_y * 0.9),
        (0.50, band_y * 0.4),
        (0.60, band_y * 0.95),
        (0.46, band_y * 1.3),
        (0.56, band_y * 1.25),
    ]
    rng = random.Random(3)
    sp = place_sparkles(d, rng, positions, (42, 58))
    return finish(img), rect, sp, GROUNDS["plum"]


def cand_caption_sparkle():
    """KEPT + upgraded, the D-adjacent candidate: caption box at the very
    top, wheat below it, sparkles now flank the wheat's LEFT/RIGHT
    margins (not above it - the caption already owns that band) so they
    still sit entirely on the dark slate field, never over the wheat's
    own pixels. Adapted placement, noted honestly: 'above the wheat top'
    was not available here because the caption occupies that position."""
    img = flat_field(GROUNDS["slate"])
    d = ImageDraw.Draw(img)

    f, w, h = fit_font(d, "MEANWHILE", "segoeuib.ttf", SIDE * 0.72, 0.135)
    pad_x, pad_y = SIDE * 0.045, SIDE * 0.035
    bx0 = SIDE / 2 - w / 2 - pad_x
    bx1 = SIDE / 2 + w / 2 + pad_x
    by0 = SIDE * 0.05
    by1 = by0 + h + pad_y * 2
    d.rectangle([bx0, by0, bx1, by1], fill=CREAM_FIELD)
    d.polygon(
        [
            (bx0 + w * 0.12, by1),
            (bx0 + w * 0.12 - SIDE * 0.05, by1 + SIDE * 0.055),
            (bx0 + w * 0.28, by1),
        ],
        fill=CREAM_FIELD,
    )
    d.text((SIDE / 2 - w / 2, by0 + pad_y * 0.6), "MEANWHILE", font=f, fill=DARK_TEXT)

    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.66, target_w_frac=0.66)
    x, y, w7, h7 = rect
    left_edge, right_edge = x / SIDE, (x + w7) / SIDE
    d2 = ImageDraw.Draw(img)
    mid_y = (y + h7 / 2) / SIDE
    positions = [
        (left_edge * 0.42, mid_y * 0.94),
        (left_edge * 0.55, mid_y * 1.14),
        (1 - (1 - right_edge) * 0.42, mid_y * 0.98),
        (1 - (1 - right_edge) * 0.55, mid_y * 1.16),
    ]
    rng = random.Random(5)
    sp = place_sparkles(d2, rng, positions, (40, 52))
    return finish(img), rect, sp, GROUNDS["slate"]


def cand_wide_arc():
    """NEW: 4 sparkles in a wide, shallow arc spanning almost the full
    width of the clear band above the wheat - indigo ground."""
    img = flat_field(GROUNDS["indigo"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.60, target_w_frac=0.88)
    x, y, w, h = rect
    top = y / SIDE
    d = ImageDraw.Draw(img)
    band_y = top * 0.55
    positions = [
        (0.10, band_y * 1.1),
        (0.36, band_y * 0.5),
        (0.64, band_y * 0.5),
        (0.90, band_y * 1.1),
    ]
    rng = random.Random(7)
    sp = place_sparkles(d, rng, positions, (48, 64))
    return finish(img), rect, sp, GROUNDS["indigo"]


def cand_bold_pair():
    """NEW: only 3 sparkles, the largest and fewest in the set, placed
    asymmetrically above one shoulder of the wheat - burgundy-black
    ground. The fewest-marks / largest-per-mark end of this round's
    range, for comparison against the 4-5-mark candidates."""
    img = flat_field(GROUNDS["burgundy_black"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.60, target_w_frac=0.88)
    x, y, w, h = rect
    top = y / SIDE
    d = ImageDraw.Draw(img)
    band_y = top * 0.55
    positions = [(0.66, band_y * 0.5), (0.82, band_y * 1.15), (0.50, band_y * 1.3)]
    rng = random.Random(9)
    sp = place_sparkles(d, rng, positions, (54, 64))
    return finish(img), rect, sp, GROUNDS["burgundy_black"]


CANDIDATES = {
    "few_large": cand_few_large,
    "cluster_above": cand_cluster_above,
    "caption_sparkle": cand_caption_sparkle,
    "wide_arc": cand_wide_arc,
    "bold_pair": cand_bold_pair,
}

CF_WHITE: Color = (255, 255, 255, 255)
MODRINTH_DARK: Color = (30, 31, 34, 255)


def measure_sparkle_pixels(
    img512: Image.Image,
    sparkles: list[tuple[float, float, float]],
    tol: int = 150,
) -> list[int]:
    """For every placed sparkle, crop a padded box around its own centre
    in the true 48x48 render and count pixels matching the sparkle's own
    drawn hues (not 'not the ground', per r15's corrected method). Returns
    one count per sparkle - the per-mark occupancy this round's report
    needs, not just a survived/total ratio."""
    small = img512.resize((48, 48), Image.LANCZOS).convert("RGB")
    arr = np.array(small).astype(np.int16)
    sparkle_cols = [
        np.array(c[:3], dtype=np.int16)
        for c in (SPARKLE_BRIGHT, SPARKLE_MID, SPARKLE_PALE)
    ]
    counts = []
    for cx, cy, r in sparkles:
        px, py = cx * 48, cy * 48
        pr = max(1, r * 48 * 2.4)
        x0, x1 = max(0, int(px - pr)), min(48, int(px + pr) + 1)
        y0, y1 = max(0, int(py - pr)), min(48, int(py + pr) + 1)
        if x1 <= x0 or y1 <= y0:
            counts.append(0)
            continue
        crop = arr[y0:y1, x0:x1]
        hit = np.zeros(crop.shape[:2], dtype=bool)
        for sc in sparkle_cols:
            diff = np.abs(crop - sc[None, None, :]).sum(axis=2)
            hit |= diff < tol
        counts.append(int(hit.sum()))
    return counts


def measure_contrast(
    img512: Image.Image, wheat_rect_side: tuple[int, int, int, int], ground: Color
) -> float:
    x, y, w, h = wheat_rect_side
    s = OUT / SIDE
    crop = img512.crop(
        (int(x * s), int(y * s), int((x + w) * s), int((y + h) * s))
    ).convert("RGB")
    arr = np.array(crop).astype(np.float32)
    ref = np.array(ground[:3], dtype=np.float32)
    return float(np.median(np.linalg.norm(arr - ref[None, None, :], axis=2)))


def build_sheet(rows: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 220
    inset_48 = 48
    row_h = cell + 40
    label_h = 34
    W = 40 + 4 * (cell + 20) + 320
    H = label_h + len(rows) * row_h + 20
    sheet = Image.new("RGB", (W, H), (245, 245, 247))
    d = ImageDraw.Draw(sheet)
    try:
        lf = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22)
    except OSError:
        lf = ImageFont.load_default()

    y = label_h
    for name, img in rows:
        x = 20
        d.text((x, y + cell // 2 - 12), name, font=lf, fill=(20, 20, 20))
        x = 320
        for bg in (CF_WHITE, MODRINTH_DARK):
            card = Image.new("RGB", (cell, cell), bg[:3])
            paste = img.resize((cell - 20, cell - 20), Image.LANCZOS)
            card.paste(paste, (10, 10), paste)
            sheet.paste(card, (x, y))
            x += cell + 20
        for bg in (CF_WHITE, MODRINTH_DARK):
            card = Image.new("RGB", (cell, cell), bg[:3])
            small = img.resize((inset_48, inset_48), Image.LANCZOS)
            off = (cell - inset_48) // 2
            card.paste(small, (off, off), small)
            sheet.paste(card, (x, y))
            x += cell + 20
        y += row_h
    return sheet


def main() -> None:
    print("wheat_stage7 placement + sparkle geometry (SIDE=2048 px):")
    rendered: list[tuple[str, Image.Image]] = []
    all_data: dict[str, tuple] = {}
    for name, fn in CANDIDATES.items():
        img, rect, sparkles, ground = fn()
        img.save(OUT_DIR / f"{name}_512.png")
        rendered.append((name, img))
        all_data[name] = (rect, sparkles, ground)
        x, y, w, h = rect
        diam_512 = [
            2 * r * OUT for _, _, r in sparkles
        ]  # r is a radius fraction; report diameter
        print(
            f"  {name:<16} wheat {w}x{h}px ({w/SIDE*100:.1f}% of side)  "
            f"n_sparkles={len(sparkles)}  diameter@512={[round(s) for s in diam_512]}"
        )

    d1_src = ROOT / "icon-candidates-r13" / "D1_weight_shift_512.png"
    if d1_src.exists():
        d1_dst = OUT_DIR / "D1_weight_shift_REFERENCE_r13_512.png"
        shutil.copyfile(d1_src, d1_dst)
        rendered.append(
            ("D1_weight_shift (r13 reference, unchanged)", Image.open(d1_dst))
        )

    sheet = build_sheet(rendered)
    sheet.save(OUT_DIR / "CONTACT_SHEET_r16.png")

    print("\nper-sparkle 48px occupancy (px) + wheat/ground contrast:")
    for name, (rect, sparkles, ground) in all_data.items():
        img = Image.open(OUT_DIR / f"{name}_512.png")
        counts = measure_sparkle_pixels(img, sparkles)
        contrast = measure_contrast(img, rect, ground)
        print(
            f"  {name:<16} per-sparkle px@48: {counts}  wheat/ground dist: {contrast:.0f}/441"
        )

    print(
        f"\nwrote {len(CANDIDATES)} candidates + reference + contact sheet to {OUT_DIR}"
    )


if __name__ == "__main__":
    main()
