# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 10. One-shot correction of round 9,
which the coordinator withdrew before showing kura: r9 is a regression
from r8, not an improvement, and does not go out.

Corrected diagnosis (this round's actual brief, not r9's guess): what
made r8's wheel read as a gear/ship's-wheel was never "it is a circle".
It was specifically "the circle has evenly-spaced round holes carved
into it" - that is yacl's own construction (a solid disc punched with
six round holes) and it is the one tell that mattered. r9 solved a
problem that was never the real one: it kept the "avoid holes" fix but
then also broke the circle itself apart (housing blocks eating half the
wheel, a waterline crop, a plain disc with no paddles at all) on the
theory that circular-and-symmetric was itself the danger. Rendered and
looked at plainly, that overcorrection killed the "wheel" reading
entirely - r9's A/B/C read as a fan or a shell, and D reads as a street
lamp or a shower head. The subject was lost solving a problem that
wasn't there.

This round keeps the disc whole (full 360deg, not occluded by a block
or a crop) and fixes only the actual tell:

  - a thin solid RIM at the outer edge (not a hollow void, a ring band)
  - flat RECTANGULAR PADDLE BOARDS attached to the *outside* of the rim,
    constant width (boards, not tapering rays), moderate count with
    visible gaps between them (gear teeth pack edge to edge; these do
    not)
  - a small solid HUB at the centre
  - a FEW straight SPOKES (3-4, not 6-12) bridging hub to rim - fewer
    spokes than a sun has rays, and spokes terminate *inside* a solid
    rim rather than crossing an open one (the thing that makes a ship's
    wheel read as a ship's wheel is spokes crossing a hollow rim with no
    paddles at all; this rim is never hollow at the very edge, and this
    wheel always has paddles, which a ship's wheel never does)
  - NO round holes anywhere - the one tell this round is not allowed to
    reintroduce
  - water is optional per candidate (a shallow flume/trough the wheel's
    lower rim just grazes, not a crop that erases half the disc) - kura
    explicitly permitted a +1 connected component if it helps sell
    "water wheel" specifically

Four wheel variants (paddle count/width, rim thickness, water on/off),
crossed against r9's three colour families (kept, not reinvented):
water blue, wheat gold, clay terracotta.

Pile fix: r8/r9's pile was one smooth isoceles triangle, which reads as
a mountain-icon, not as accumulated output - kura's own diagnosis this
round. Replaced with a squat, flat-bottomed bin/crate (implies "this is
where output collects", LOGO_PLAYBOOK's container instinct) holding an
irregular cluster of overlapping lumps of different sizes (sacks/
boulders, a discrete-units silhouette, not one continuous slope). Pile
keeps the area majority over the wheel that r8 was chosen for - measured
per candidate below, not asserted.

SS=4: 2048px composite -> 512 LANCZOS, square ground, gentle vertical
lightening (sodium/lithium source numbers, see r7's docstring), pure
white glyph, background-coloured cutouts for interior detail. No
vanilla texture pasted.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r10"
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


def mid(top_rgb: tuple[int, int, int], bot_rgb: tuple[int, int, int]) -> Color:
    return tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)


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


def board_poly(
    cx: float, cy: float, ang_deg: float, r_in: float, length: float, width: float
) -> list[tuple[float, float]]:
    """Flat rectangular paddle board, constant width - attached to the
    OUTSIDE of a solid rim (r_in = rim's outer radius), not floating
    off a bare hub. Being anchored to a continuous rim band, rather than
    emanating from empty space around a small hub, is what keeps this
    from reading as a sun ray even with the wheel kept fully circular."""
    local = [
        (r_in, -width / 2),
        (r_in + length, -width / 2),
        (r_in + length, width / 2),
        (r_in, width / 2),
    ]
    pts = []
    for lx, ly in local:
        wx, wy = cx + lx, cy + ly
        wx, wy = rot((wx, wy), (cx, cy), ang_deg)
        pts.append(P(wx, wy))
    return pts


# ============================================================== wheel ===


def wheel_wagon(
    d: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    r: float,
    n_paddle: int,
    paddle_w_frac: float,
    paddle_len_frac: float,
    rim_thick_frac: float,
    n_spoke: int,
    bg_mid: Color,
) -> None:
    """Rim + few straight spokes + small hub + paddle boards outside the
    rim - the wagon-wheel grammar this round's brief specifies. The
    circle is kept whole (full 360deg); the only thing ever cut is the
    thin annular band between hub and rim (standard wheel construction,
    not a hole punched through a solid disc - the hub itself stays
    solid, which is what a punched hole is not)."""
    hub_r = r * 0.16
    rim_outer = r
    rim_inner = r * (1 - rim_thick_frac)

    # solid full disc, then open the spoked interior (leaving the rim
    # band solid at the outside edge)
    d.ellipse(
        [*P(cx - rim_outer, cy - rim_outer), *P(cx + rim_outer, cy + rim_outer)],
        fill=WHITE,
    )
    # cut to fully transparent, not bg_mid - the wheel sits high in the
    # frame where the gradient's local colour is well off the top/bottom
    # average bg_mid represents (r9's waterline-slab bug, same class):
    # a flat bg_mid fill here would show as a visibly mismatched patch
    # inside the wedges rather than blending with the field
    d.ellipse(
        [*P(cx - rim_inner, cy - rim_inner), *P(cx + rim_inner, cy + rim_inner)],
        fill=(0, 0, 0, 0),
    )

    # hub, solid, back in white
    d.ellipse([*P(cx - hub_r, cy - hub_r), *P(cx + hub_r, cy + hub_r)], fill=WHITE)

    # spokes bridging hub to rim - few, straight, terminate inside a
    # solid rim (never crossing an open one, unlike a ship's wheel)
    spoke_w = r * 0.075
    for i in range(n_spoke):
        ang = 360 / n_spoke * i + 20
        local = [
            (hub_r * 0.85, -spoke_w / 2),
            (rim_inner * 1.02, -spoke_w / 2),
            (rim_inner * 1.02, spoke_w / 2),
            (hub_r * 0.85, spoke_w / 2),
        ]
        pts = []
        for lx, ly in local:
            wx, wy = cx + lx, cy + ly
            wx, wy = rot((wx, wy), (cx, cy), ang)
            pts.append(P(wx, wy))
        d.polygon(pts, fill=WHITE)

    # paddle boards, outside the rim, full circle, constant width,
    # visible gaps between them (not flush-packed like gear teeth)
    blade_len = r * paddle_len_frac
    blade_w = r * paddle_w_frac
    for i in range(n_paddle):
        ang = 360 / n_paddle * i + 12
        d.polygon(board_poly(cx, cy, ang, rim_outer, blade_len, blade_w), fill=WHITE)


def draw_flume(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, bg_mid: Color
) -> None:
    """Shallow trough the wheel's lower rim just grazes - a separate
    touching shape, not a crop through the wheel. +1 connected
    component, explicitly permitted this round if it helps sell "water
    wheel"."""
    top_y = cy + r * 0.72
    d.polygon(
        [
            P(cx - r * 1.15, top_y),
            P(cx - r * 0.95, top_y + r * 0.30),
            P(cx + r * 0.95, top_y + r * 0.30),
            P(cx + r * 1.15, top_y),
            P(cx + r * 1.0, top_y - r * 0.04),
            P(cx - r * 1.0, top_y - r * 0.04),
        ],
        fill=WHITE,
    )
    for i in range(2):
        yy = top_y + r * 0.12 + i * r * 0.10
        pts = []
        for j in range(9):
            t = j / 8
            x = cx - r * 0.85 + r * 1.7 * t
            wob = yy + math.sin(t * math.pi * 3 + i) * r * 0.02
            pts.append(P(x, wob))
        d.line(pts, fill=bg_mid, width=int(r * 0.03 * SIDE), joint="curve")


# =============================================================== pile ===


def draw_pile(d: ImageDraw.ImageDraw, base_y: float, cx: float, width: float) -> None:
    """A bin holding an irregular cluster of lumps - not one smooth
    triangle. kura's own diagnosis this round: a smooth isoceles slope
    reads as a "mountain" icon, not as "output that accumulated"; a
    container implies collection, and separate overlapping lumps of
    different sizes read as discrete units (sacks/boulders) rather than
    a continuous landform."""
    hw = width / 2
    wall_top = base_y - width * 0.20

    # bin: flared side walls + flat bottom, a squat trapezoid trough
    d.polygon(
        [
            P(cx - hw * 1.05, wall_top),
            P(cx - hw * 0.72, base_y),
            P(cx + hw * 0.72, base_y),
            P(cx + hw * 1.05, wall_top),
            P(cx + hw * 0.90, wall_top),
            P(cx + hw * 0.60, base_y - width * 0.05),
            P(cx - hw * 0.60, base_y - width * 0.05),
            P(cx - hw * 0.90, wall_top),
        ],
        fill=WHITE,
    )

    # irregular cluster of overlapping lumps, uneven peaks, sitting in
    # and above the bin's open top - hand-placed for a controlled but
    # lumpy (not smooth) silhouette
    lumps = [
        (cx - hw * 0.55, wall_top + width * 0.02, hw * 0.34),
        (cx - hw * 0.18, wall_top - width * 0.10, hw * 0.40),
        (cx + hw * 0.22, wall_top - width * 0.02, hw * 0.36),
        (cx + hw * 0.58, wall_top + width * 0.03, hw * 0.30),
        (cx - hw * 0.02, wall_top - width * 0.34, hw * 0.30),
        (cx + hw * 0.34, wall_top - width * 0.26, hw * 0.24),
        (cx - hw * 0.34, wall_top - width * 0.24, hw * 0.22),
    ]
    for lx, ly, lr in lumps:
        d.ellipse([*P(lx - lr, ly - lr), *P(lx + lr, ly + lr)], fill=WHITE)


# ========================================================== candidates ===

WATER = ((44, 128, 198), (92, 166, 224))
WHEAT = ((214, 160, 46), (228, 190, 108))
CLAY = ((198, 92, 52), (216, 130, 92))

WHEEL_CX, WHEEL_CY, WHEEL_R = 0.335, 0.335, 0.185


def build(
    n_paddle: int,
    paddle_w: float,
    paddle_len: float,
    rim_thick: float,
    n_spoke: int,
    with_flume: bool,
    colours,
):
    top_rgb, bot_rgb = colours
    field = ground(top_rgb, bot_rgb)
    bg_mid = mid(top_rgb, bot_rgb)

    wheel_layer, dw = canvas()
    pile_layer, dp = canvas()

    wheel_wagon(
        dw,
        WHEEL_CX,
        WHEEL_CY,
        WHEEL_R,
        n_paddle,
        paddle_w,
        paddle_len,
        rim_thick,
        n_spoke,
        bg_mid,
    )
    if with_flume:
        draw_flume(dw, WHEEL_CX, WHEEL_CY, WHEEL_R, bg_mid)
    draw_pile(dp, base_y=0.87, cx=0.58, width=0.74)

    composed = field.copy()
    composed.alpha_composite(pile_layer)
    composed.alpha_composite(wheel_layer)
    return finish(composed), wheel_layer, pile_layer


def ink(img: Image.Image) -> int:
    return int((np.array(img.split()[3]) > 10).sum())


CANDIDATES = {
    # name: (n_paddle, paddle_w_frac, paddle_len_frac, rim_thick_frac, n_spoke, with_flume, colours)
    # width > length on every candidate (deliberately): a ship's wheel's
    # handles are long, thin pegs (length clearly exceeds width, often
    # with a ball tip); making the protrusion *wide and short* - a flat
    # tab, not a peg - is the one proportion left to lean on for keeping
    # this a paddle board rather than a helm handle, now that the rim/
    # spoke/hub structure itself is (correctly, per kura's own diagnosis)
    # no longer being avoided.
    "8paddle_thickrim_water_blue": (8, 0.22, 0.12, 0.16, 3, True, WATER),
    "6paddle_thinrim_water_wheat": (6, 0.26, 0.14, 0.09, 4, True, WHEAT),
    "5paddle_medrim_nowater_clay": (5, 0.30, 0.15, 0.12, 3, False, CLAY),
    "8paddle_thinrim_water_clay": (8, 0.20, 0.11, 0.08, 4, True, CLAY),
    "6paddle_thickrim_nowater_blue": (6, 0.25, 0.13, 0.17, 3, False, WATER),
}


def main() -> None:
    print(f"{'name':<30} {'wheel px':>10} {'pile px':>10} {'wheel%':>8}")
    for name, params in CANDIDATES.items():
        n_paddle, pw, pl, rim, nsp, flume, colours = params
        img, wheel_layer, pile_layer = build(n_paddle, pw, pl, rim, nsp, flume, colours)
        img.save(OUT_DIR / f"{name}_512.png")
        w_ink, p_ink = ink(wheel_layer), ink(pile_layer)
        print(f"{name:<30} {w_ink:>10} {p_ink:>10} {w_ink/(w_ink+p_ink)*100:>7.1f}%")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
