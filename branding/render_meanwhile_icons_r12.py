# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 12. r11's structural fix (solid disk,
short paddle fringe, real falling/flowing water) still didn't clear:
kura's read was "sun or virus", not water wheel.

Coordinator's own constraint, retracted this round: "white silhouette on
a saturated flat field" was never something kura said - it was derived
from sodium (a white droplet) and lithium (a white feather) and then
applied as a blanket rule for ten rounds. Re-reading kura's own named
anchors shows the single-colour-silhouette rule doesn't hold across the
set: the "function-guessable" group (appleskin, the-block-keeps-ticking,
unloaded-activity, xaero) is entirely multi-colour, and part of the
"brand-strong" group is too (architectury: black field, orange crane).
A droplet and a feather are shapes that read fine as a flat monochrome
silhouette; a wheel built of wood, next to water, is not - kura's point
this round is that ten rounds of reshaping the same white outline never
had a chance of fixing that, because the actual problem was the colour
constraint, not the geometry.

This round keeps every structural number measured from Create's real
water_wheel.obj in r11 (see r11's docstring for the full method: solid
disk out to ~76-80% radius, hub ~18%, paddle fringe the outer ~20-24%,
measured by parsing all `v x y z` lines and bucketing by radius from
centre - not re-measured here, the geometry wasn't the thing kura
flagged) and adds colour: the wheel's wood body, its darker plank-seam
lines (new this round - "木の板の目地を描いてよい", makes the disk read
as built-of-boards rather than a flat disc), and the water in a blue
distinct from both the wood and the ground field. 3-5 colours per
candidate, negative_list.md's bans (gradient glow, badge circles, glowing
dots) still enforced regardless of colour count.

Colour choices (from the subject, not decoration):
  wood body     #965F33  H27  S65% V59%  (mid oak-brown)
  wood seams    #6A421F  H26  S66% V42%  (~17pt darker - plank joints
                                           and hub shadow, not a
                                           different material)
  wood highlight #C08A5C H29  S50% V75%  (a rim/spoke catch-light,
                                           used sparingly, not a 3rd
                                           material)
  water         #2E6FA8  H207 S73% V66%  (deep enough to hold contrast
                                           against a water-blue field,
                                           where a droplet-bright blue
                                           would nearly vanish)
  water spray   #EAF4FC H201 S13% V96%   (near-white foam/highlight on
                                           splashes only, not the whole
                                           water body)

Ground fields keep the three families used since r9 (kura raised no
objection): water blue, wheat gold, clay terracotta - chosen so every
candidate keeps a large value gap between field and wood body (the
explicit instruction this round), never paired so field and wood sit
within ~15pt of V of each other.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r12"
OUT_DIR.mkdir(exist_ok=True)

SIDE = 2048
OUT = 512
Color = tuple[int, int, int, int]

WOOD: Color = (150, 95, 51, 255)
WOOD_DARK: Color = (106, 66, 31, 255)
WOOD_LIGHT: Color = (192, 138, 92, 255)
WATER: Color = (46, 111, 168, 255)
WATER_DEEP: Color = (28, 78, 128, 255)
SPRAY: Color = (234, 244, 252, 255)


def P(fx: float, fy: float) -> tuple[float, float]:
    return (fx * SIDE, fy * SIDE)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def ground(
    top_rgb: tuple[int, int, int], bottom_rgb: tuple[int, int, int]
) -> Image.Image:
    rows = np.linspace(0, 1, SIDE)[:, None]
    top = np.array(top_rgb, dtype=np.float32)
    bot = np.array(bottom_rgb, dtype=np.float32)
    grad = top[None, :] + (bot - top[None, :]) * rows
    arr = np.repeat(grad[:, None, :], SIDE, axis=1).astype(np.uint8)
    alpha = np.full((SIDE, SIDE, 1), 255, dtype=np.uint8)
    return Image.fromarray(np.concatenate([arr, alpha], axis=2), "RGBA")


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def rot(
    pt: tuple[float, float], origin: tuple[float, float], deg: float
) -> tuple[float, float]:
    ox, oy = origin
    x, y = pt[0] - ox, pt[1] - oy
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return (ox + x * ca - y * sa, oy + x * sa + y * ca)


def nub_poly(
    cx: float,
    cy: float,
    ang_deg: float,
    r_in: float,
    length: float,
    width: float,
    squash: float = 1.0,
) -> list[tuple[float, float]]:
    local = [
        (r_in, -width / 2),
        (r_in + length, -width / 2),
        (r_in + length, width / 2),
        (r_in, width / 2),
    ]
    pts = []
    for lx, ly in local:
        wx, wy = cx + lx, cy + ly * squash
        wx, wy = rot((wx, wy), (cx, cy), ang_deg)
        pts.append(P(wx, wy))
    return pts


# ============================================================== wheel ===


def draw_wheel(
    d: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    r: float,
    n_paddle: int,
    paddle_len_frac: float,
    paddle_w_frac: float,
    squash: float,
    n_planks: int,
    use_highlight: bool,
) -> None:
    """Same measured proportions as r11 (disk to ~76% radius, hub ~15%,
    paddle fringe the outer ~20-24%), now built of visibly distinct
    wood: a mid-brown body, darker plank-seam lines dividing it into
    boards (this round's explicit addition), paddles and hub in the
    same wood body colour (they are the same material, not a different
    part), and an optional thin highlight catch-light along the rim."""
    disk_r = r * 0.76

    def ell(rad, fill, sq=squash):
        d.ellipse([*P(cx - rad, cy - rad * sq), *P(cx + rad, cy + rad * sq)], fill=fill)

    ell(disk_r, WOOD)

    # plank seams - darker radial lines dividing the disk into boards
    for i in range(n_planks):
        ang = 360 / n_planks * i + 8
        x1, y1 = (
            cx + math.cos(math.radians(ang)) * disk_r * 0.16,
            cy + math.sin(math.radians(ang)) * disk_r * 0.16 * squash,
        )
        x2, y2 = (
            cx + math.cos(math.radians(ang)) * disk_r * 0.96,
            cy + math.sin(math.radians(ang)) * disk_r * 0.96 * squash,
        )
        d.line([P(x1, y1), P(x2, y2)], fill=WOOD_DARK, width=int(r * 0.028 * SIDE))

    # hub, a darker wood disc (shadow, not a different material)
    hub_r = r * 0.15
    ell(hub_r, WOOD_DARK)
    ell(hub_r * 0.55, WOOD)

    if use_highlight:
        # thin catch-light arc along the upper-left rim
        d.arc(
            [
                *P(cx - disk_r, cy - disk_r * squash),
                *P(cx + disk_r, cy + disk_r * squash),
            ],
            200,
            300,
            fill=WOOD_LIGHT,
            width=int(r * 0.035 * SIDE),
        )

    blade_len = r * paddle_len_frac
    blade_w = r * paddle_w_frac
    for i in range(n_paddle):
        ang = 360 / n_paddle * i + 10
        d.polygon(nub_poly(cx, cy, ang, disk_r, blade_len, blade_w, squash), fill=WOOD)
        # seam on each paddle - reads as its board's own grain line
        lx1, ly1 = (
            cx + math.cos(math.radians(ang)) * disk_r * 1.02,
            cy + math.sin(math.radians(ang)) * disk_r * 1.02 * squash,
        )
        lx2, ly2 = (
            cx + math.cos(math.radians(ang)) * (disk_r + blade_len) * 0.96,
            cy + math.sin(math.radians(ang)) * (disk_r + blade_len) * 0.96 * squash,
        )
        d.line(
            [P(lx1, ly1), P(lx2, ly2)],
            fill=WOOD_DARK,
            width=max(2, int(r * 0.012 * SIDE)),
        )


# =============================================================== water ===


def wavy_band(
    cx: float,
    y: float,
    half_w: float,
    amp: float,
    turns: float,
    n: int = 13,
    phase: float = 0.0,
) -> list[tuple[float, float]]:
    pts = []
    for i in range(n):
        t = i / (n - 1)
        x = cx - half_w + 2 * half_w * t
        yy = y + math.sin(t * math.pi * turns + phase) * amp
        pts.append(P(x, yy))
    return pts


def draw_undershot(
    d: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    r: float,
    squash: float,
    n_bands: int = 4,
) -> None:
    top_y = cy + r * squash * 0.55
    for i in range(n_bands):
        yy = top_y + i * r * 0.16
        col = WATER if i % 2 == 0 else WATER_DEEP
        d.line(
            wavy_band(cx, yy, r * 1.35, r * 0.055, 3.2, phase=i * 1.3),
            fill=col,
            width=int(r * 0.11 * SIDE),
            joint="curve",
        )
    # foam fleck at the wheel/water meeting point
    d.ellipse(
        [*P(cx - r * 0.05, top_y - r * 0.05), *P(cx + r * 0.05, top_y + r * 0.05)],
        fill=SPRAY,
    )


def draw_overshot(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, squash: float
) -> None:
    spout_y = cy - r * 1.75
    d.rounded_rectangle(
        [*P(cx - r * 0.24, spout_y - r * 0.11), *P(cx + r * 0.24, spout_y + r * 0.11)],
        radius=int(r * 0.055 * SIDE),
        fill=WOOD_DARK,
    )
    y0 = spout_y + r * 0.11
    y1 = cy - r * squash * 0.45
    for i, sx in enumerate((-0.28, 0.0, 0.28)):
        x0 = cx + r * sx
        pts = []
        for j in range(9):
            t = j / 8
            yy = y0 + (y1 - y0) * t
            xx = x0 + math.sin(t * math.pi * 1.6 + i) * r * 0.11
            pts.append(P(xx, yy))
        col = WATER if i != 1 else WATER_DEEP
        d.line(pts, fill=col, width=int(r * 0.145 * SIDE), joint="curve")


def draw_splash(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, squash: float
) -> None:
    base_y = cy + r * squash * 0.80
    for dx, dy, s, col in (
        (-0.60, 0.10, 0.13, WATER),
        (-0.22, 0.22, 0.17, SPRAY),
        (0.22, 0.18, 0.15, WATER),
        (0.60, 0.07, 0.12, SPRAY),
    ):
        cx2, cy2 = cx + r * dx, base_y + r * dy
        d.polygon(
            [
                P(cx2, cy2 - r * s),
                P(cx2 + r * s * 0.75, cy2 + r * s * 0.5),
                P(cx2, cy2 + r * s * 0.85),
                P(cx2 - r * s * 0.75, cy2 + r * s * 0.5),
            ],
            fill=col,
        )


def ink(img: Image.Image) -> int:
    return int((np.array(img.split()[3]) > 10).sum())


# ========================================================== candidates ===

BG_WATER = ((44, 128, 198), (92, 166, 224))
BG_WHEAT = ((214, 160, 46), (228, 190, 108))
BG_CLAY = ((198, 92, 52), (216, 130, 92))


def build(
    kind: str,
    n_paddle: int,
    paddle_len: float,
    paddle_w: float,
    squash: float,
    n_planks: int,
    use_highlight: bool,
    colours,
) -> tuple[Image.Image, Image.Image, Image.Image]:
    top_rgb, bot_rgb = colours
    field = ground(top_rgb, bot_rgb)

    cx, cy, r = 0.42, 0.44, 0.225

    wheel_layer, dw = canvas()
    water_layer, dwater = canvas()

    draw_wheel(
        dw, cx, cy, r, n_paddle, paddle_len, paddle_w, squash, n_planks, use_highlight
    )

    if kind == "undershot":
        draw_undershot(dwater, cx, cy, r, squash, n_bands=4)
    elif kind == "overshot":
        draw_overshot(dwater, cx, cy, r, squash)
        draw_splash(dwater, cx, cy, r, squash)
    elif kind == "both":
        draw_overshot(dwater, cx, cy, r, squash)
        draw_undershot(dwater, cx, cy, r, squash, n_bands=3)
    elif kind == "undershot_big":
        draw_undershot(dwater, cx, cy, r, squash, n_bands=5)
        draw_splash(dwater, cx, cy, r, squash)

    composed = field.copy()
    composed.alpha_composite(water_layer)
    composed.alpha_composite(wheel_layer)
    return finish(composed), wheel_layer, water_layer


CANDIDATES = {
    # name: (kind, n_paddle, paddle_len_frac, paddle_w_frac, squash, n_planks, use_highlight, colours)
    "3color_overshot_blue": ("overshot", 9, 0.24, 0.16, 1.0, 6, False, BG_WATER),
    "4color_undershot_wheat": ("undershot", 10, 0.22, 0.14, 1.0, 8, True, BG_WHEAT),
    "4color_overshot_angled_clay": ("overshot", 8, 0.26, 0.18, 0.62, 6, True, BG_CLAY),
    "5color_both_blue": ("both", 8, 0.24, 0.17, 1.0, 8, True, BG_WATER),
    "3color_undershot_angled_clay": (
        "undershot_big",
        9,
        0.23,
        0.15,
        0.62,
        6,
        False,
        BG_CLAY,
    ),
}


def main() -> None:
    print(f"{'name':<28} {'wheel px':>10} {'water px':>10} {'water%':>8}")
    for name, params in CANDIDATES.items():
        kind, n_paddle, plen, pw, squash, nplk, hl, colours = params
        img, wheel_layer, water_layer = build(
            kind, n_paddle, plen, pw, squash, nplk, hl, colours
        )
        img.save(OUT_DIR / f"{name}_512.png")
        w_ink, wa_ink = ink(wheel_layer), ink(water_layer)
        total = w_ink + wa_ink
        print(f"{name:<28} {w_ink:>10} {wa_ink:>10} {wa_ink/total*100:>7.1f}%")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
