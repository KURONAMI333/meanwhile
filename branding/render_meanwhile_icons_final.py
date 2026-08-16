# -*- coding: utf-8 -*-
"""Meanwhile store icon - CONFIRMED (2026-08-16).

kura picked `wide_arc` from round 16's 5 candidates. Final, no further
selection needed - this file exists so a future session doesn't have to
re-derive or re-pick the icon; it reproduces exactly the confirmed image
and nothing else. If the icon ever needs to change again, that is a new
round with a new file, not an edit to this one.

## Lineage (why this is the shape it is)

16 rounds total. Rounds 1-12 (not in this file's history) all failed on
"Subject-Max": drawing one object can't express "meanwhile" (a relation
between two states), and the coordinator kept trying anyway. Rounds
13-15 explored two-panel here/there devices (r13: drawn blade tufts,
r14: real wheat_stage0/7 texture crops either side of a gutter) - kura's
r14 verdict was that the device itself was the problem: wheat_stage0 has
only 7 non-transparent pixels, so half of every composition was spent on
a term that could never carry its half at store-icon size. r15 dropped
the second panel entirely: one subject (wheat_stage7, grown, filling the
frame) plus vanilla's own symbol for "a growth event just happened" (the
green sparkle shown when bonemeal fires) substitutes for the "before"
state instead of depicting it - a player who has ever bonemealed a crop
reads it instantly. r15's own sparkles were too small (drawn from
r15->r16, r15 measured 2-7 surviving px per mark at 48px - not enough
for a four-pointed shape to read as a shape). r16 fixed that: fewer,
much bigger marks (40-64px diameter at the 512 stage), placed strictly
above the wheat's own bounding-box top so none of them ever blend into
the wheat's own olive-shaded pixels. kura chose `wide_arc` from r16's 5:
4 sparkles in a wide, shallow arc, each one individually isolated (no
overlap between marks, unlike `cluster_above`'s tighter grouping) -
r16's report called this "the best individual isolation... every mark is
a clean, separated 4-point star."

## What's real pixel data vs drawn

`wheat_stage7.png` is real vanilla pixel art (from
`_tools/MineTexture/tool_data/cache/vanilla_textures/block/`), 16x16,
full-tile content bbox, scaled by an exact integer NEAREST factor (no
resampling of the sprite itself - only the final SS=4 -> target-size
step uses LANCZOS). The 4 sparkles are DRAWN (an 8-vertex 4-point-star
polygon, flat fill, no glow) - no vanilla happy_villager particle sprite
was found in the MineTexture cache or anywhere else in this repo (see
r15's docstring for the search), so this is a from-observation glyph,
not a pasted asset. Reported honestly as such at every round.

## Confirmed values

  ground colour     indigo #1B2340  (H231 S59% V25%)
  wheat crop        wheat_stage7.png, bbox (0,0)-(15,15) = full 16x16
                     tile, integer NEAREST x120, centred at (0.5, 0.60)
                     of the canvas, target width 88% of canvas side
  sparkle count      4
  sparkle colours     #92E85C (bright lime) / #56B03E (mid green) /
                       #D6F5B0 (pale core) - cycled, not one flat tint
  sparkle diameters   53, 58, 57, 49 px at the 512 stage (measured;
                       target band was 40-64px)
  sparkle positions   (0.10, 0.55h) (0.36, 0.25h) (0.64, 0.25h)
                       (0.90, 0.55h), where h = wheat-bbox-top-fraction
                       x 0.55 (a wide, shallow arc spanning almost the
                       full canvas width, entirely above the wheat)
  seed                random.Random(7) - reproduces the exact rotations
                       and per-sparkle sizes r16 measured

## Output

  branding/meanwhile_icon_512.png / _256.png / _128.png / _64.png
      all resized from the same SIDE=2048 supersample master via one
      LANCZOS pass each (not chained through 512 repeatedly)
  src/main/resources/logo.png
      the 256px version, for the in-jar `logoFile` reference (added by
      the coordinator to neoforge.mods.toml separately - this file only
      places the PNG)
"""

from __future__ import annotations

import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
MOD_ROOT = ROOT.parent
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
Color = tuple[int, int, int, int]

GROUND: Color = (27, 35, 64, 255)  # #1B2340 indigo

SPARKLE_BRIGHT: Color = (146, 232, 92, 255)  # #92E85C
SPARKLE_MID: Color = (86, 176, 62, 255)  # #56B03E
SPARKLE_PALE: Color = (214, 245, 176, 255)  # #D6F5B0

WHEAT_CX, WHEAT_CY, WHEAT_W_FRAC = 0.5, 0.60, 0.88
SPARKLE_SEED = 7
SPARKLE_DIAM_RANGE_512 = (48, 64)  # target diameter band at the 512 stage
SPARKLE_X_FRACS = (0.10, 0.36, 0.64, 0.90)
SPARKLE_BAND_MULT = (1.1, 0.5, 0.5, 1.1)  # per-sparkle multiplier on band_y


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
    target_px = target_w_frac * SIDE
    scale = max(1, round(target_px / sprite.width))
    scaled = sprite.resize((sprite.width * scale, sprite.height * scale), Image.NEAREST)
    x = int(cx * SIDE - scaled.width / 2)
    y = int(cy * SIDE - scaled.height / 2)
    dest.alpha_composite(scaled, (x, y))
    return x, y, scaled.width, scaled.height


def sparkle_polygon(
    cx: float, cy: float, r_out: float, rot: float
) -> list[tuple[float, float]]:
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


def render_master() -> Image.Image:
    """The SIDE=2048 supersample master - every exported size is one
    LANCZOS resize away from this, never from another resized copy."""
    img = Image.new("RGBA", (SIDE, SIDE), GROUND)
    s7 = load_sprite_cropped("wheat_stage7.png")
    rect = place_sprite_nearest(img, s7, WHEAT_CX, WHEAT_CY, WHEAT_W_FRAC)
    _, y, _, _ = rect
    top = y / SIDE
    band_y = top * 0.55

    d = ImageDraw.Draw(img)
    rng = random.Random(SPARKLE_SEED)
    colours = [SPARKLE_BRIGHT, SPARKLE_MID, SPARKLE_PALE, SPARKLE_BRIGHT]
    for i, (xf, mult) in enumerate(zip(SPARKLE_X_FRACS, SPARKLE_BAND_MULT)):
        diam = rng.uniform(*SPARKLE_DIAM_RANGE_512)
        r_frac = diam / (SIDE / 2)  # diameter@512 -> radius fraction of SIDE
        rot = rng.uniform(0, 45)
        draw_sparkle(d, xf, band_y * mult, r_frac, rot, colours[i % len(colours)])
    return img


def main() -> None:
    master = render_master()

    sizes = {
        512: "meanwhile_icon_512.png",
        256: "meanwhile_icon_256.png",
        128: "meanwhile_icon_128.png",
        64: "meanwhile_icon_64.png",
    }
    for size, filename in sizes.items():
        out = master.resize((size, size), Image.LANCZOS)
        out.save(ROOT / filename)
        print(f"wrote {ROOT / filename}  ({size}x{size})")

    logo_dst = MOD_ROOT / "src" / "main" / "resources" / "logo.png"
    master.resize((256, 256), Image.LANCZOS).save(logo_dst)
    print(f"wrote {logo_dst}  (256x256, in-jar logo)")


if __name__ == "__main__":
    main()
