# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 8 candidates.

Round 7 verdict (kura, this session): "デザインはどれもいいね" - approved,
first pass to clear after six rejected rounds. What changed was not the
subject matter, it was the *method*: measure sodium/lithium's actual
pixels first (ground gradient, square corners, glyph colour, bbox/margin/
component counts - see render_meanwhile_icons_r7.py's docstring), write
the numbers down as a target, then draw to them, rather than measuring
and then drawing from memory anyway. That method is kept unchanged this
round (same ground()/composite()/finish()/leaf_polygon() helpers, copied
verbatim from r7, not reinvented).

kura's follow-up question after approving r7 - "MODの内容的に一番近い
ロゴはどれなの" - surfaced a gap the coordinator had introduced by fiat:
r6/r7 banned furnace/hopper as *literal vanilla screenshots*, and in
doing so dropped "a machine" from the subject list entirely, even though
this mod's whole job is block-entity machines. None of r7's nine subjects
(hourglass, sprout, moon, pendulum, wind-up key, beehive, shell, cocoon,
book) touch the mod's actual differentiator - "it also catches up mods'
machines, not just vanilla" - because none of them are a machine. This
round's brief is that one axis, drawn in the same register.

The brief also names the exact mistake to avoid, one r7 already made
twice (pendulum, wind-up key): "動き続けていた" is not what this mod
does. The mod's own store copy says so directly - "None of this happens
while you're away" - chunks are genuinely unloaded, and the catch-up
runs in one shot on reload. The correct claim is "progressed by the time
you got back", a before/after state, not motion sustained through the
absence. Every candidate below is therefore posed at rest (no spin
lines, no blur, no mid-turn crank), and carries a second element in the
same glyph that is not the machine but the *residue* of it having run -
a pile, a swept dial arc, a delivered block, a tally, an overflowed rim,
a loaded balance pan - so the machine alone is never the whole story.

Eight subjects (LOGO_PLAYBOOK "6種類以上", no colour-only repeats):
waterwheel + grain pile, millstone (side profile, twin drum) + flour
pile, piston + the block it already delivered downstream, pressure
gauge + swept arc, conveyor lip + stacked crates, hand crank + flywheel
+ tally notches, cauldron filled to overflow, balance scale tipped
under a loaded pan. Colour taken from each subject's own material
(water blue, wheat gold, mechanism indigo, gauge warning red-orange,
construction amber, brass-olive, potion green, precision teal) per
LOGO_PLAYBOOK's colour-selection ruling (2026-07-30: don't dodge
collision, the subject decides the colour).

Banned motifs (all rounds' cumulative list plus this round's own
"gear" caution): furnace front, hopper, plain right-arrow, raw->cooked
pair, vanilla clock face, strata swatch, chunk grid, diamond pair,
flame, bracket/ring/annulus as the whole mark, world diorama, text/
wordmark/monogram, gear (yacl owns it - dropped from the subject list
outright rather than risking a near-miss), and all nine of r7's own
subjects. The conveyor+crates subject is deliberately NOT a funnel/
hopper-spout shape (that silhouette is the banned object itself) - it
is a flat platform edge with crates stacked past it. The gauge's swept
arc is a background-coloured pie cut into the solid disc (same carve
technique as r7's moon crescent and hourglass sand-line), not a second
ring drawn around it, staying clear of the ring ban the same way r7's
wind-up key avoided it by using a solid tab instead of a loop.

Motif collision against the 33 local reference icons (branding/gate0/
icons/*_orig.*, reviewed via the labelled contact sheet built for r7,
not by filename guess): none of the eight subjects below appear there.
yacl's flower-cog is the only gear-family icon in the set and is not
drawn here at all.

SS=4: 2048px composite -> 512 LANCZOS, square ground, flat colour with
the same gentle vertical lightening measured off sodium/lithium in r7,
pure white glyph, background-coloured cutouts for interior detail.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r8"
OUT_DIR.mkdir(exist_ok=True)

SIDE = 2048  # SS=4 composite size
OUT = 512

Color = tuple[int, int, int, int]


# ============================================================== geometry ===
# (identical to render_meanwhile_icons_r7.py - same register, not reinvented)


def P(fx: float, fy: float) -> tuple[float, float]:
    return (fx * SIDE, fy * SIDE)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def leaf_polygon(
    base: tuple[float, float],
    tip: tuple[float, float],
    bulge: float,
    n: int = 24,
) -> list[tuple[float, float]]:
    bx, by = base
    tx, ty = tip
    dx, dy = tx - bx, ty - by
    length = math.hypot(dx, dy)
    if length == 0:
        return [P(bx, by)]
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    pts_fwd, pts_back = [], []
    for i in range(n + 1):
        t = i / n
        cx = bx + dx * t
        cy = by + dy * t
        w = bulge * math.sin(math.pi * t)
        pts_fwd.append((cx + px * w, cy + py * w))
        pts_back.append((cx - px * w, cy - py * w))
    pts = pts_fwd + pts_back[::-1]
    return [P(x, y) for x, y in pts]


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


def composite(field: Image.Image, glyph: Image.Image) -> Image.Image:
    out = field.copy()
    out.alpha_composite(glyph)
    return out


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


WHITE: Color = (255, 255, 255, 255)


# =================================================================== #1 ===
# waterwheel + grain pile - the pile carries the visual weight, not the
# wheel; wheel drawn at rest (no spin lines) so it reads as "this made
# that pile", not "this is still turning"


def cand_waterwheel() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (44, 128, 198)  # water blue, H208 S78% V78%
    bot_rgb = (92, 166, 224)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    # wheel: solid disc, upper-left, smaller than the pile
    cx, cy, r = 0.335, 0.335, 0.185
    d.ellipse([*P(cx - r, cy - r), *P(cx + r, cy + r)], fill=WHITE)
    # paddle notches carved at rest (not blurred)
    for ang in range(0, 360, 60):
        a = math.radians(ang)
        nx, ny = cx + math.cos(a) * r * 0.62, cy + math.sin(a) * r * 0.62
        d.ellipse([*P(nx - 0.028, ny - 0.028), *P(nx + 0.028, ny + 0.028)], fill=bg_mid)
    # axle post
    d.rectangle([*P(cx - 0.02, cy + r - 0.01), *P(cx + 0.02, 0.62)], fill=WHITE)

    # grain pile: wide mound, dominant lower-right, with a few kernels
    mound = [
        P(0.24, 0.86),
        P(0.30, 0.62),
        P(0.42, 0.50),
        P(0.58, 0.46),
        P(0.72, 0.52),
        P(0.80, 0.66),
        P(0.83, 0.86),
    ]
    d.polygon(mound, fill=WHITE)
    for kx, ky in [(0.52, 0.44), (0.60, 0.415), (0.68, 0.45)]:
        d.ellipse([*P(kx - 0.024, ky - 0.024), *P(kx + 0.024, ky + 0.024)], fill=WHITE)

    return composite(field, glyph), top_rgb


# =================================================================== #2 ===
# millstone (side profile, two stacked drums, not a top-down ring) +
# flour spilling out at the base


def cand_millstone() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (214, 160, 46)  # wheat gold, H42 S79% V84%
    bot_rgb = (228, 190, 108)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    # bed stone (lower, wider drum)
    d.rounded_rectangle(
        [*P(0.24, 0.52), *P(0.76, 0.70)], radius=int(0.04 * SIDE), fill=WHITE
    )
    d.ellipse([*P(0.24, 0.475), *P(0.76, 0.565)], fill=WHITE)
    # runner stone (upper, narrower drum)
    d.rounded_rectangle(
        [*P(0.32, 0.30), *P(0.68, 0.50)], radius=int(0.035 * SIDE), fill=WHITE
    )
    d.ellipse([*P(0.32, 0.255), *P(0.68, 0.345)], fill=WHITE)
    d.ellipse([*P(0.32, 0.455), *P(0.68, 0.545)], fill=bg_mid)
    # crank nub on top
    d.rectangle([*P(0.475, 0.20), *P(0.525, 0.26)], fill=WHITE)

    # flour pile spilling from under the bed stone
    pile = [P(0.16, 0.86), P(0.30, 0.68), P(0.50, 0.63), P(0.70, 0.68), P(0.84, 0.86)]
    d.polygon(pile, fill=WHITE)

    return composite(field, glyph), top_rgb


# =================================================================== #3 ===
# piston + the block it already pushed downstream (a gap between the
# piston head and the block is the "already delivered" evidence)


def cand_piston() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (66, 74, 168)  # mechanism indigo, H232 S61% V66%
    bot_rgb = (104, 112, 196)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    d.rectangle([*P(0.14, 0.34), *P(0.36, 0.66)], fill=WHITE)  # casing
    d.rectangle([*P(0.36, 0.455), *P(0.62, 0.545)], fill=WHITE)  # rod
    d.rectangle([*P(0.62, 0.40), *P(0.70, 0.60)], fill=WHITE)  # piston head
    # gap, then the delivered block, further along than the arm reaches
    d.rectangle(
        [*P(0.80, 0.34), *P(0.98, 0.66)], fill=WHITE
    )  # rounded via corner notch below
    # soften the delivered block's outer corners slightly for distinction
    # from the casing (small triangular corner cuts)
    bg_mid = mid(top_rgb, bot_rgb)
    cut = 0.03
    for x0, y0, sx, sy in [
        (0.80, 0.34, 1, 1),
        (0.98, 0.34, -1, 1),
        (0.80, 0.66, 1, -1),
        (0.98, 0.66, -1, -1),
    ]:
        d.polygon([P(x0, y0), P(x0 + sx * cut, y0), P(x0, y0 + sy * cut)], fill=bg_mid)

    return composite(field, glyph), top_rgb


# =================================================================== #4 ===
# pressure gauge: solid disc kept fully intact (a wedge cut deep enough
# to read the swept arc made the first pass look like a pac-man wedge,
# not a dial - fixed by moving the cut to a thin rim band instead, so
# the disc still reads as a circle) with a needle parked far from a
# 12-o'clock start tick


def cand_gauge() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (208, 62, 34)  # gauge red-orange, H10 S84% V82%
    bot_rgb = (224, 108, 78)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    cx, cy, r = 0.50, 0.52, 0.34
    d.ellipse([*P(cx - r, cy - r), *P(cx + r, cy + r)], fill=WHITE)
    # swept rim band already covered (12 o'clock = -90deg, clockwise to
    # +140deg) - a band near the edge, not a pie eating the whole disc,
    # so the circle silhouette stays intact
    d.arc(
        [*P(cx - r, cy - r), *P(cx + r, cy + r)],
        -90,
        140,
        fill=bg_mid,
        width=int(r * 0.30 * SIDE),
    )
    # needle: a white-on-white line is invisible on the solid disc, so
    # it is carved (background-coloured groove) instead, the same trick
    # as the hourglass's sand-thread - visible along its whole length,
    # not just where it happens to cross the rim band
    ang = math.radians(140)
    nx, ny = cx + math.cos(ang) * r * 0.90, cy + math.sin(ang) * r * 0.90
    d.line([P(cx, cy), P(nx, ny)], fill=bg_mid, width=int(0.024 * SIDE))
    d.ellipse([*P(cx - 0.032, cy - 0.032), *P(cx + 0.032, cy + 0.032)], fill=bg_mid)
    # start-mark tick at 12 o'clock, carved into the rim band
    d.rectangle(
        [*P(cx - 0.016, cy - r - 0.012), *P(cx + 0.016, cy - r * 0.62)], fill=WHITE
    )

    return composite(field, glyph), top_rgb


# =================================================================== #5 ===
# conveyor edge + stacked crates (not a funnel/hopper spout - that
# silhouette is the banned object)


def cand_crates() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (222, 132, 30)  # construction amber, H33 S86% V87%
    bot_rgb = (236, 168, 88)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    # platform edge
    d.rectangle([*P(0.14, 0.34), *P(0.62, 0.40)], fill=WHITE)
    d.rectangle([*P(0.14, 0.40), *P(0.20, 0.60)], fill=WHITE)  # support leg

    def crate(x0, y0, s):
        d.rectangle([*P(x0, y0), *P(x0 + s, y0 + s)], fill=WHITE)
        d.rectangle([*P(x0 + s * 0.42, y0), *P(x0 + s * 0.58, y0 + s)], fill=bg_mid)
        d.rectangle([*P(x0, y0 + s * 0.42), *P(x0 + s, y0 + s * 0.58)], fill=bg_mid)

    crate(0.30, 0.58, 0.22)
    crate(0.54, 0.58, 0.22)
    crate(0.42, 0.36, 0.22)

    return composite(field, glyph), top_rgb


# =================================================================== #6 ===
# hand crank + flywheel, parked (handle pointing down, not mid-turn) +
# a cluster of tally notches recording completed turns


def cand_crank() -> tuple[Image.Image, tuple[int, int, int]]:
    """First pass put tick marks radiating from the disc's rim - at a
    glance that read as a stopwatch/clock face (the exact banned motif),
    not a crank. Fixed twice: the rim ticks are gone, and the "turns
    logged" evidence moved off the disc entirely into its own small
    tally badge, so nothing radiates from a circle anywhere in this
    glyph."""
    top_rgb = (150, 132, 40)  # brass-olive, H50 S63% V59%
    bot_rgb = (182, 166, 78)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    cx, cy, r = 0.38, 0.44, 0.20
    d.ellipse([*P(cx - r, cy - r), *P(cx + r, cy + r)], fill=WHITE)
    # eccentric crank pin, offset from centre (reads as a mechanism, not
    # a clock hand, because it stops at the pin - no hand sweeps past it)
    pin_ang = math.radians(-35)
    px, py = cx + math.cos(pin_ang) * r * 0.62, cy + math.sin(pin_ang) * r * 0.62
    d.ellipse([*P(px - 0.028, py - 0.028), *P(px + 0.028, py + 0.028)], fill=bg_mid)

    # crank arm: pin -> out past the rim -> grip knob, at rest
    rim_ang = math.radians(-35)
    rx, ry = cx + math.cos(rim_ang) * r, cy + math.sin(rim_ang) * r
    hx, hy = cx + math.cos(rim_ang) * r * 1.85, cy + math.sin(rim_ang) * r * 1.85
    d.line([P(rx, ry), P(hx, hy)], fill=WHITE, width=int(0.034 * SIDE))
    d.ellipse([*P(hx - 0.046, hy - 0.046), *P(hx + 0.046, hy + 0.046)], fill=WHITE)

    # tally badge, off the wheel entirely - turns already logged
    bx0, by0, bx1, by1 = 0.62, 0.62, 0.86, 0.80
    d.rounded_rectangle(
        [*P(bx0, by0), *P(bx1, by1)], radius=int(0.012 * SIDE), fill=WHITE
    )
    for i in range(5):
        tx = bx0 + 0.03 + i * 0.038
        d.line(
            [P(tx, by0 + 0.03), P(tx, by1 - 0.03)], fill=bg_mid, width=int(0.012 * SIDE)
        )
    # fifth tally struck diagonally through the first four (classic count-of-5)
    d.line(
        [P(bx0 + 0.03, by1 - 0.03), P(bx0 + 0.03 + 3 * 0.038, by0 + 0.03)],
        fill=bg_mid,
        width=int(0.012 * SIDE),
    )

    return composite(field, glyph), top_rgb


# =================================================================== #7 ===
# cauldron filled to overflow - the drip is the evidence, cauldron is
# static (no bubbling motion lines)


def cand_cauldron() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (30, 140, 92)  # potion green, H154 S62% V55%
    bot_rgb = (68, 172, 122)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()
    bg_mid = mid(top_rgb, bot_rgb)

    body = [
        P(0.22, 0.38),
        P(0.78, 0.38),
        P(0.74, 0.72),
        P(0.62, 0.80),
        P(0.38, 0.80),
        P(0.26, 0.72),
    ]
    d.polygon(body, fill=WHITE)
    d.rectangle([*P(0.16, 0.335), *P(0.84, 0.395)], fill=WHITE)  # rim lip
    # legs
    for lx in (0.28, 0.50, 0.72):
        d.polygon(
            [
                P(lx - 0.035, 0.78),
                P(lx + 0.035, 0.78),
                P(lx + 0.02, 0.88),
                P(lx - 0.02, 0.88),
            ],
            fill=WHITE,
        )
    # fill line near the rim (carved) - shows it's brimming, not empty
    d.rectangle([*P(0.26, 0.42), *P(0.74, 0.45)], fill=bg_mid)
    # overflow drips escaping the rim
    for dx in (0.185, 0.815):
        d.polygon(
            leaf_polygon(
                (dx, 0.40), (dx + (0.02 if dx < 0.5 else -0.02), 0.50), 0.018, n=10
            ),
            fill=WHITE,
        )

    return composite(field, glyph), top_rgb


# =================================================================== #8 ===
# balance scale tipped under a loaded pan - static, no swinging


def cand_scale() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (32, 140, 146)  # precision teal, H186 S62% V57%
    bot_rgb = (70, 172, 178)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    # stand
    d.polygon([P(0.46, 0.20), P(0.54, 0.20), P(0.58, 0.86), P(0.42, 0.86)], fill=WHITE)
    d.rectangle([*P(0.30, 0.84), *P(0.70, 0.90)], fill=WHITE)  # base
    # beam, tilted - left low (loaded), right high (empty)
    d.line([P(0.20, 0.42), P(0.80, 0.26)], fill=WHITE, width=int(0.026 * SIDE))
    d.ellipse([*P(0.485, 0.20), *P(0.515, 0.23)], fill=WHITE)  # pivot

    # left string + loaded pan with a piled load
    d.line([P(0.20, 0.42), P(0.20, 0.58)], fill=WHITE, width=int(0.012 * SIDE))
    d.line([P(0.11, 0.62), P(0.29, 0.62)], fill=WHITE, width=int(0.10 * SIDE))
    d.polygon(
        [P(0.155, 0.58), P(0.20, 0.50), P(0.245, 0.58), P(0.22, 0.60), P(0.18, 0.60)],
        fill=WHITE,
    )

    # right string + empty pan, higher up
    d.line([P(0.80, 0.26), P(0.80, 0.38)], fill=WHITE, width=int(0.012 * SIDE))
    d.line([P(0.735, 0.41), P(0.865, 0.41)], fill=WHITE, width=int(0.055 * SIDE))

    return composite(field, glyph), top_rgb


CANDIDATES = {
    "waterwheel": cand_waterwheel,
    "millstone": cand_millstone,
    "piston": cand_piston,
    "gauge": cand_gauge,
    "crates": cand_crates,
    "crank": cand_crank,
    "cauldron": cand_cauldron,
    "scale": cand_scale,
}


def main() -> None:
    for name, fn in CANDIDATES.items():
        img, _top = fn()
        finish(img).save(OUT_DIR / f"{name}_512.png")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
