# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 11. kura's direction (this session):
"建物は描写せず、水車と水流にフォーカスして見てくれ　イメージはCreate" -
no buildings/housing/bins (r8's housing block, r10's bin are both
dropped), no pile as a co-subject (r7-r10 all made the pile the
majority of the frame; this round drops it entirely, per kura's own
"山を残すかどうかも含めて水車と水流に集中した構図で考えてください").
Subject is exactly two things: the wheel and the water, and the water
must carry equal visual weight, not be a faint accessory - kura's
diagnosis of why gear/helm/ferris-wheel readings survived ten rounds:
every version drew a circle-plus-radiating-structure and left water as
a thin afterthought. A circle with a real, bold water context can only
read as a water wheel; the geometry alone was never going to fully
settle it.

Reference: Create mod's own water_wheel block, read directly rather
than from memory (LOGO_PLAYBOOK's rule + this session's find-the-real-
thing instruction). Source and measurements below - no texture pixels
pasted, this round draws flat redrawn silhouettes at the measured
proportions, same register as r7-r10.

  jar found: mod-047-music-disc-maker-ml/neoforge/compat-libs/
    create-1.21.1-6.0.10.jar (assets/create/models/block/water_wheel/
    water_wheel.obj)
  decompiled source (used instead, easier to read):
    mod-research/sources/Create/src/main/resources/assets/create/
    models/block/water_wheel/water_wheel.obj (808 vertices, Blender
    export) + water_wheel/block.json + blockstates/water_wheel.json

  Measured (script: parse all `v x y z` lines, centre at (0.5,0.5),
  radius = hypot(x-0.5, z-0.5) - the blockstate's "facing=up" variant
  carries zero rotation, confirming the model's native rotation axis is
  Y and the paddle structure lives in the XZ plane, i.e. this radius is
  exactly the face-on silhouette this icon needs):
    hub (axle box corner)        r ~ 0.177  (~18% of paddle-tip radius)
    main disk/body               r 0.30-0.85, 608 of 808 verts (75%) -
                                  the wheel is visually a SOLID DISK out
                                  to ~80% radius, not open spokes around
                                  a bare hub
    paddle fringe                r 0.85-1.00, 128 of 808 verts (16%) -
                                  paddles are a SHORT band at the very
                                  edge (~20% of the radius), not long
                                  boards reaching toward the centre
    paddle count (angular        ~16 clusters of paddle-tip vertices
    clustering of tip verts)     around 360deg (leading+trailing edge
                                  pairs per paddle -> ~8 physical
                                  paddles, very densely spaced)

  Design targets taken from this (this round's numbers-before-drawing
  step): hub ~15-18% of radius, solid disk body out to ~72-80%, paddles
  a short fringe from there to the outer edge (~20-28% of radius, never
  long rays), paddle count compressed from Create's ~8-16 (illegible as
  individual boards at 48px) down to 8-10 for icon legibility while
  keeping them SHORT - this preserves the measured proportion (short
  fringe, not long blades) rather than the literal count.

Because the disk is solid (not hollow spokes around a bare hub), this
also sidesteps r10's residual ship's-wheel-adjacency concern structurally
rather than through proportion alone: a ship's wheel's defining trait is
a HOLLOW rim with spokes crossing open air; this wheel's interior is
never open.

Four axes varied across candidates per the brief: paddle count/width,
wheel tilt (front-on circle vs angled ellipse), water form (overshot
chute, undershot flume, falling water with splash), and water:wheel
area ratio - measured per candidate below, not asserted. Ground colours
carried over from r9/r10 (kura raised no objection to them): water blue,
wheat gold, clay terracotta.

SS=4: 2048px composite -> 512 LANCZOS, square ground, gentle vertical
lightening (sodium/lithium source numbers, r7's docstring), pure white
glyph, background-coloured cutouts for interior detail, negative_list.md
observed (no gradiented glow, no badge circles). No vanilla or
third-party texture pixels pasted - silhouette and proportion only.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r11"
OUT_DIR.mkdir(exist_ok=True)

SIDE = 2048
OUT = 512
Color = tuple[int, int, int, int]
WHITE: Color = (255, 255, 255, 255)


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
    n_spoke_lines: int,
    bg_mid: Color,
) -> None:
    """Solid disk (hub->~76% radius, matching the measured Create model)
    + a short paddle fringe at the outer ~24% + a few light carved spoke
    accents (surface linework, not open wedges - the disk stays solid,
    which is the structural difference from a ship's wheel's hollow
    rim). squash<1.0 draws an ellipse instead of a circle for the
    angled-wheel candidates (a genuinely different silhouette family
    from every gear/sun/helm/ferris-wheel reference, all of which are
    true circles)."""
    disk_r = r * 0.76

    def ell(rad, fill):
        d.ellipse(
            [*P(cx - rad, cy - rad * squash), *P(cx + rad, cy + rad * squash)],
            fill=fill,
        )

    ell(disk_r, WHITE)
    # light spoke accents, carved - surface detail only, disk stays solid
    for i in range(n_spoke_lines):
        ang = 360 / n_spoke_lines * i + 15
        x1, y1 = (
            cx + math.cos(math.radians(ang)) * disk_r * 0.18,
            cy + math.sin(math.radians(ang)) * disk_r * 0.18 * squash,
        )
        x2, y2 = (
            cx + math.cos(math.radians(ang)) * disk_r * 0.88,
            cy + math.sin(math.radians(ang)) * disk_r * 0.88 * squash,
        )
        d.line([P(x1, y1), P(x2, y2)], fill=bg_mid, width=int(r * 0.028 * SIDE))
    hub_r = r * 0.15
    ell(hub_r, WHITE)

    blade_len = r * paddle_len_frac
    blade_w = r * paddle_w_frac
    for i in range(n_paddle):
        ang = 360 / n_paddle * i + 10
        d.polygon(nub_poly(cx, cy, ang, disk_r, blade_len, blade_w, squash), fill=WHITE)


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
    bg_mid: Color,
    n_bands: int = 4,
) -> None:
    """Flume: a run of bold, thick wavy bands at the wheel's base, the
    wheel's lower rim grazing the top band - undershot construction."""
    top_y = cy + r * squash * 0.55
    for i in range(n_bands):
        yy = top_y + i * r * 0.16
        w = int(r * 0.11 * SIDE)
        d.line(
            wavy_band(cx, yy, r * 1.35, r * 0.055, 3.2, phase=i * 1.3),
            fill=WHITE,
            width=w,
            joint="curve",
        )


def draw_overshot(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, squash: float, bg_mid: Color
) -> None:
    """Spout + falling water ribbons onto the wheel's top rim - overshot
    construction. First pass (dead end, kept on record): a flat
    trapezoid "chute" above the wheel read as a roof/awning, not water -
    a triangle with straight edges reads as architecture regardless of
    what it's labelled. Fixed by dropping the chute shape entirely and
    drawing the water itself: a short spout nub (just large enough to
    read as an opening, not a structure) with three thick S-curve
    ribbons falling from it, wide enough to carry real visual weight
    rather than being thin accent lines."""
    spout_y = cy - r * 1.75
    d.rounded_rectangle(
        [*P(cx - r * 0.24, spout_y - r * 0.11), *P(cx + r * 0.24, spout_y + r * 0.11)],
        radius=int(r * 0.055 * SIDE),
        fill=WHITE,
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
        d.line(pts, fill=WHITE, width=int(r * 0.145 * SIDE), joint="curve")


def draw_splash(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, squash: float
) -> None:
    """A few droplet shapes at the base - falling water landing."""
    base_y = cy + r * squash * 0.80
    for dx, dy, s in (
        (-0.60, 0.10, 0.13),
        (-0.22, 0.22, 0.17),
        (0.22, 0.18, 0.15),
        (0.60, 0.07, 0.12),
    ):
        cx2, cy2 = cx + r * dx, base_y + r * dy
        d.polygon(
            [
                P(cx2, cy2 - r * s),
                P(cx2 + r * s * 0.75, cy2 + r * s * 0.5),
                P(cx2, cy2 + r * s * 0.85),
                P(cx2 - r * s * 0.75, cy2 + r * s * 0.5),
            ],
            fill=WHITE,
        )


def ink(img: Image.Image) -> int:
    return int((np.array(img.split()[3]) > 10).sum())


# ========================================================== candidates ===

WATER = ((44, 128, 198), (92, 166, 224))
WHEAT = ((214, 160, 46), (228, 190, 108))
CLAY = ((198, 92, 52), (216, 130, 92))


def mid(top_rgb, bot_rgb) -> Color:
    return tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)


def build(
    kind: str,
    n_paddle: int,
    paddle_len: float,
    paddle_w: float,
    squash: float,
    n_spoke: int,
    colours,
) -> tuple[Image.Image, Image.Image, Image.Image]:
    top_rgb, bot_rgb = colours
    field = ground(top_rgb, bot_rgb)
    bg_mid = mid(top_rgb, bot_rgb)

    cx, cy, r = 0.42, 0.44, 0.225

    wheel_layer, dw = canvas()
    water_layer, dwater = canvas()

    draw_wheel(dw, cx, cy, r, n_paddle, paddle_len, paddle_w, squash, n_spoke, bg_mid)

    if kind == "undershot":
        draw_undershot(dwater, cx, cy, r, squash, bg_mid, n_bands=4)
    elif kind == "overshot":
        draw_overshot(dwater, cx, cy, r, squash, bg_mid)
        draw_splash(dwater, cx, cy, r, squash)
    elif kind == "both":
        draw_overshot(dwater, cx, cy, r, squash, bg_mid)
        draw_undershot(dwater, cx, cy, r, squash, bg_mid, n_bands=3)
    elif kind == "undershot_big":
        draw_undershot(dwater, cx, cy, r, squash, bg_mid, n_bands=5)
        draw_splash(dwater, cx, cy, r, squash)

    composed = field.copy()
    composed.alpha_composite(water_layer)
    composed.alpha_composite(wheel_layer)
    return finish(composed), wheel_layer, water_layer


CANDIDATES = {
    # name: (kind, n_paddle, paddle_len_frac, paddle_w_frac, squash, n_spoke, colours)
    "overshot_front_blue": ("overshot", 9, 0.24, 0.16, 1.0, 3, WATER),
    "undershot_front_wheat": ("undershot", 10, 0.22, 0.14, 1.0, 4, WHEAT),
    "overshot_angled_clay": ("overshot", 8, 0.26, 0.18, 0.62, 3, CLAY),
    "both_front_blue": ("both", 8, 0.24, 0.17, 1.0, 3, WATER),
    "undershot_angled_clay": ("undershot_big", 9, 0.23, 0.15, 0.62, 4, CLAY),
}


def main() -> None:
    print(f"{'name':<24} {'wheel px':>10} {'water px':>10} {'water%':>8}")
    for name, params in CANDIDATES.items():
        kind, n_paddle, plen, pw, squash, nsp, colours = params
        img, wheel_layer, water_layer = build(
            kind, n_paddle, plen, pw, squash, nsp, colours
        )
        img.save(OUT_DIR / f"{name}_512.png")
        w_ink, wa_ink = ink(wheel_layer), ink(water_layer)
        total = w_ink + wa_ink
        print(f"{name:<24} {w_ink:>10} {wa_ink:>10} {wa_ink/total*100:>7.1f}%")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
