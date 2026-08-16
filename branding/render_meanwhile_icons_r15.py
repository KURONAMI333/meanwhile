# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 15.

kura's read of r14: the split-panel device itself is the problem.
wheat_stage0 measured at only 7 non-transparent px (round 14's own
finding) and no placement ever gave it more than a "small green dot"
presence - half the icon was structurally spent on a term that can't
carry its half. kura's fix: stop drawing two panels. One subject -
wheat_stage7, grown, filling the frame, matching the direct-competitor
bbox norm (gate0's median is ~100%) - plus vanilla's own symbol for "a
growth event just happened": the green sparkle Minecraft shows when
bonemeal (or any instant-grow) fires. A player who has ever bonemealed
a crop reads that symbol instantly, no second panel required - the
event substitutes for the "before" term instead of depicting it.

Particle texture search (before drawing anything, negative_list #33):
  _tools/MineTexture/tool_data/cache/vanilla_textures/particle/ has no
  file named happy_villager - not surprising, this cache holds terrain/
  block/item/particle *sprites*, not the particles.json type-to-sprite
  mapping, and no vanilla client jar with assets/minecraft/particles/
  was found in this repo either (checked: no particles.json, no
  happy_villager.json anywhere under _tools/MineTexture). What IS in
  particle/: effect_0..7.png (8x8, an animated comma/spiral - the
  potion-swirl sprite used for entity_effect/ambient_entity_effect),
  critical_hit.png (8x8 checkerboard dither), angry.png (8x8, the
  villager-anger stormcloud icon - confirmed by its shape, not this
  round's target), glint.png (8x8 diagonal enchant stripe), heart.png /
  goldheart_*.png (breeding), note.png (jukebox). None of these is a
  four-pointed sparkle, and none is authoritatively confirmed as the
  happy_villager sprite (that mapping lives in particles.json, not
  found in this cache) - so per this round's own instruction, this
  file does NOT paste any of them. draw_sparkle() below is a drawn
  four-pointed star/glint glyph (two crossed elongated diamonds), the
  standard flat "sparkle" convention, coloured from observation of the
  in-game particle (a spread of 2-3 discrete greens, not one flat
  tint - real bonemeal sparkles visibly vary in shade). This is a
  drawn shape, reported honestly as such, not a real-pixel asset.

wheat_stage7.png IS real pixel data (16x16, full-tile bbox, same file
r14 used) - cropped to its own bbox and scaled by an integer factor
with NEAREST, exactly like r14. Only the sparkles are drawn.
"""

from __future__ import annotations

import math
import random
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r15"
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

# sparkle greens - a spread of shades, not one flat tint (observed: real
# bonemeal particles vary visibly in brightness)
SPARKLE_BRIGHT: Color = (146, 232, 92, 255)  # #92E85C lime, the brightest
SPARKLE_MID: Color = (86, 176, 62, 255)  # #56B03E mid green
SPARKLE_PALE: Color = (214, 245, 176, 255)  # #D6F5B0 near-white-green core

DARK_TEXT: Color = (30, 24, 16, 255)
CREAM_FIELD: Color = (231, 227, 218, 255)

GROUNDS = {
    "indigo": (27, 35, 64, 255),  # #1B2340
    "teal": (15, 59, 58, 255),  # #0F3B3A
    "plum": (52, 27, 61, 255),  # #341B3D
    "near_black": (22, 24, 28, 255),  # #16181C
    "slate": (38, 48, 66, 255),  # #263042
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
    """Integer-NEAREST scale, centred on (cx, cy) - this round's subject
    is centred, not baseline-anchored (there is no second panel to share
    a ground line with). Returns the pasted (x, y, w, h) in SIDE px."""
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
    """A flat 4-point star/glint glyph: two crossed elongated diamonds,
    8 vertices alternating outer/inner radius. No glow, no gradient -
    the point shape itself reads as 'sparkle' (negative_list-compliant:
    shape and colour carry it, not a blur)."""
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


def scatter_sparkles(
    d: ImageDraw.ImageDraw,
    rng: random.Random,
    n: int,
    x_range: tuple[float, float],
    y_range: tuple[float, float],
    size_range: tuple[float, float],
) -> list[tuple[float, float, float]]:
    """Places n sparkles at random (seeded, reproducible) positions/sizes
    within the given fractional box, cycling the 3 greens so no single
    candidate is a flat one-tone field of marks. Returns (cx, cy, r_frac)
    for each - used later to isolate crops for the 48px measurement."""
    colours = [SPARKLE_BRIGHT, SPARKLE_MID, SPARKLE_PALE, SPARKLE_BRIGHT, SPARKLE_MID]
    placed = []
    for i in range(n):
        cx = rng.uniform(*x_range)
        cy = rng.uniform(*y_range)
        r = rng.uniform(*size_range) / SIDE
        rot = rng.uniform(0, 45)
        draw_sparkle(d, cx, cy, r, rot, colours[i % len(colours)])
        placed.append((cx, cy, r))
    return placed


# ============================================================ candidates ===


def cand_dense_scatter():
    """Wheat fills the frame (direct-competitor norm). 11 small-to-mid
    sparkles scattered broadly over the whole crop - a busy 'just
    bonemealed' moment."""
    img = flat_field(GROUNDS["indigo"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.54, target_w_frac=0.94)
    d = ImageDraw.Draw(img)
    rng = random.Random(1)
    sp = scatter_sparkles(d, rng, 11, (0.08, 0.92), (0.06, 0.80), (18, 32))
    return finish(img), rect, sp, GROUNDS["indigo"]


def cand_few_large():
    """Wheat fills the frame. 5 larger sparkles, weighted toward the
    upper band above the wheat tops - fewer, bolder marks."""
    img = flat_field(GROUNDS["teal"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.55, target_w_frac=0.94)
    d = ImageDraw.Draw(img)
    rng = random.Random(2)
    sp = scatter_sparkles(d, rng, 5, (0.14, 0.86), (0.04, 0.34), (34, 48))
    return finish(img), rect, sp, GROUNDS["teal"]


def cand_cluster_above():
    """Wheat fills the frame. 8 mid sparkles tightly clustered in one
    zone just above the wheat centre - a single 'burst' rather than an
    even scatter."""
    img = flat_field(GROUNDS["plum"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.56, target_w_frac=0.94)
    d = ImageDraw.Draw(img)
    rng = random.Random(3)
    sp = scatter_sparkles(d, rng, 8, (0.32, 0.68), (0.06, 0.28), (20, 34))
    return finish(img), rect, sp, GROUNDS["plum"]


def cand_margin_breathing():
    """The one candidate with headroom, per instruction: wheat at ~78%
    width instead of ~94%, clear margin on all sides, sparkles scattered
    generously into the open field as well as over the crop."""
    img = flat_field(GROUNDS["near_black"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.56, target_w_frac=0.76)
    d = ImageDraw.Draw(img)
    rng = random.Random(4)
    sp = scatter_sparkles(d, rng, 9, (0.06, 0.94), (0.04, 0.88), (20, 32))
    return finish(img), rect, sp, GROUNDS["near_black"]


def cand_caption_sparkle():
    """The one A+D-adjacent candidate this round (kura wants D kept
    alive): a 'MEANWHILE' caption box across the top, wheat filling most
    of the remaining frame below it, sparkles over the crop."""
    img = flat_field(GROUNDS["slate"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.62, target_w_frac=0.86)
    d = ImageDraw.Draw(img)
    rng = random.Random(5)
    sp = scatter_sparkles(d, rng, 7, (0.14, 0.86), (0.34, 0.86), (20, 32))

    f, w, h = fit_font(d, "MEANWHILE", "segoeuib.ttf", SIDE * 0.72, 0.135)
    pad_x, pad_y = SIDE * 0.045, SIDE * 0.035
    bx0 = SIDE / 2 - w / 2 - pad_x
    bx1 = SIDE / 2 + w / 2 + pad_x
    by0 = SIDE * 0.06
    by1 = by0 + h + pad_y * 2
    d.rectangle([bx0, by0, bx1, by1], fill=CREAM_FIELD)
    d.polygon(
        [
            (bx0 + w * 0.12, by1),
            (bx0 + w * 0.12 - SIDE * 0.05, by1 + SIDE * 0.06),
            (bx0 + w * 0.28, by1),
        ],
        fill=CREAM_FIELD,
    )
    d.text((SIDE / 2 - w / 2, by0 + pad_y * 0.6), "MEANWHILE", font=f, fill=DARK_TEXT)
    return finish(img), rect, sp, GROUNDS["slate"]


def cand_mixed_size_scatter():
    """Wheat fills the frame. A deliberate size hierarchy - 3 large + 7
    small sparkles - instead of the uniform sizing in dense_scatter."""
    img = flat_field(GROUNDS["burgundy_black"])
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, 0.5, 0.54, target_w_frac=0.94)
    d = ImageDraw.Draw(img)
    rng = random.Random(6)
    sp_big = scatter_sparkles(d, rng, 3, (0.15, 0.85), (0.05, 0.30), (40, 50))
    sp_small = scatter_sparkles(d, rng, 7, (0.08, 0.92), (0.10, 0.78), (14, 22))
    return finish(img), rect, sp_big + sp_small, GROUNDS["burgundy_black"]


CANDIDATES = {
    "dense_scatter": cand_dense_scatter,
    "few_large": cand_few_large,
    "cluster_above": cand_cluster_above,
    "margin_breathing": cand_margin_breathing,
    "caption_sparkle": cand_caption_sparkle,
    "mixed_size_scatter": cand_mixed_size_scatter,
}

CF_WHITE: Color = (255, 255, 255, 255)
MODRINTH_DARK: Color = (30, 31, 34, 255)


def measure_sparkle_survival(
    img512: Image.Image,
    sparkles: list[tuple[float, float, float]],
    ground: Color,
    tol: int = 150,
) -> tuple[int, int]:
    """For every placed sparkle, crop a small pad around its own centre in
    the 48px render and test for the sparkle's OWN drawn colours
    (SPARKLE_BRIGHT/MID/PALE), not merely 'not the ground colour'. The
    first version of this function tested against-ground only, which is
    wrong for any sparkle placed over the wheat: wheat pixels differ from
    the ground too, so that version silently counted 'found wheat nearby'
    as 'sparkle survived'. Testing the sparkle's own hue avoids that
    contamination. Returns (sparkles_with_a_surviving_pixel, total)."""
    small = img512.resize((48, 48), Image.LANCZOS).convert("RGB")
    arr = np.array(small).astype(np.int16)
    sparkle_cols = [
        np.array(c[:3], dtype=np.int16)
        for c in (SPARKLE_BRIGHT, SPARKLE_MID, SPARKLE_PALE)
    ]
    survived = 0
    for cx, cy, r in sparkles:
        px, py = cx * 48, cy * 48
        pr = max(1, r * 48 * 2.2)
        x0, x1 = max(0, int(px - pr)), min(48, int(px + pr) + 1)
        y0, y1 = max(0, int(py - pr)), min(48, int(py + pr) + 1)
        if x1 <= x0 or y1 <= y0:
            continue
        crop = arr[y0:y1, x0:x1]
        hit = False
        for sc in sparkle_cols:
            diff = np.abs(crop - sc[None, None, :]).sum(axis=2)
            if (diff < tol).any():
                hit = True
                break
        if hit:
            survived += 1
    return survived, len(sparkles)


def measure_contrast(
    img512: Image.Image, wheat_rect_side: tuple[int, int, int, int], ground: Color
) -> float:
    """Median colour distance between the wheat crop region and the flat
    ground - a single number for 'did we get value separation this
    time', unlike r14's amber-on-amber near-miss."""
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
    print("wheat_stage7 placement (integer NEAREST, px at SIDE=2048):")
    rendered: list[tuple[str, Image.Image]] = []
    for name, fn in CANDIDATES.items():
        img, rect, sparkles, ground = fn()
        img.save(OUT_DIR / f"{name}_512.png")
        rendered.append((name, img))
        x, y, w, h = rect
        print(
            f"  {name:<20} wheat_stage7  {w}x{h}px  ({w/SIDE*100:.1f}% of side)  sparkles={len(sparkles)}"
        )

    d1_src = ROOT / "icon-candidates-r13" / "D1_weight_shift_512.png"
    if d1_src.exists():
        d1_dst = OUT_DIR / "D1_weight_shift_REFERENCE_r13_512.png"
        shutil.copyfile(d1_src, d1_dst)
        rendered.append(
            ("D1_weight_shift (r13 reference, unchanged)", Image.open(d1_dst))
        )

    sheet = build_sheet(rendered)
    sheet.save(OUT_DIR / "CONTACT_SHEET_r15.png")

    print("\nsparkle 48px survival + wheat/ground contrast:")
    for name, fn in CANDIDATES.items():
        img, rect, sparkles, ground = fn()
        survived, total = measure_sparkle_survival(img, sparkles, ground)
        contrast = measure_contrast(img, rect, ground)
        print(
            f"  {name:<20} sparkles surviving @48px: {survived}/{total}   "
            f"wheat-vs-ground median colour distance: {contrast:.0f}/441"
        )

    print(
        f"\nwrote {len(CANDIDATES)} candidates + reference + contact sheet to {OUT_DIR}"
    )


if __name__ == "__main__":
    main()
