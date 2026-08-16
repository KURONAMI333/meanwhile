# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 9. Waterwheel only, kura's pick out of
round 8, requested adjustment: the wheel currently reads as a gear /
ship's wheel / ferris wheel / sun, not a waterwheel.

Diagnosis of the r8 wheel (render_meanwhile_icons_r8.py, cand_waterwheel):
a solid disc with six small background-coloured dots carved evenly
around the rim. Evenly-spaced round holes on a disc is exactly the
"flower gear" grammar yacl's own icon uses (see branding/gate0/icons/
yacl_orig.webp - a disc with six punched holes) and reads equally well
as a ship's wheel's bolt/handle ring at small size.

FIRST PASS OF THIS ROUND ALSO FAILED, differently, and the failure is
worth keeping on record rather than quietly overwriting: three "paddle"
treatments were drawn with long, thin, evenly-spaced radial blades
(length:width ~2-4:1) on a free-floating hub. Rendered and looked at
plainly, all three read as a six-point sun / pinwheel-toy / compass
rose, not a waterwheel - see the git history of this file for the
discarded geometry. The lesson: "paddles" and "sun rays" are the *same*
silhouette grammar (thin shapes evenly radiating from a hub) and no
amount of tweaking taper or count fixes that alone. Two changes fixed
it, together, not separately:

  1. Paddles were shortened and widened to near-square (~1.1:1), so
     each one reads as a flat scoop/panel, not a ray. Rays are visually
     defined by being long and thin; killing the elongation kills the
     sun-reading at its root.
  2. Every candidate's wheel now sits with its lower rim overlapping a
     carved (background-coloured) wavy stream band, not floating in
     blank field. A free-floating symmetric radial mark is exactly what
     a gear/ship's-wheel/ferris-wheel/sun all are; anchoring the wheel
     in a physical context (water) is what disambiguates a waterwheel
     from that family in real flat-icon sets, more reliably than
     silhouette tweaks alone. The stream is static (a fixed wavy line,
     not a flow arrow or motion streak) so it does not reintroduce the
     "kept turning" claim the brief explicitly rules out.

Three wheel treatments, kept structurally different (not three
recolours of one shape):

  A  tangential - hub + 6 short, wide paddles swept off pure-radial
     (like a pinwheel toy's blade attachment, but no longer proportioned
     like one - see above).
  B  radial - hub + 5 short, wide paddles attached straight-radial, no
     sweep, no spokes, no rim ring (a ship's wheel needs an open rim for
     its spokes to read as a wheel at all; this hub is never hollow).
  C  asymmetric mount - paddles only on the lower ~160deg arc, the side
     that sits in the stream, plus a visible axle post and support leg
     off to the side. Radially-symmetric-all-around is what a gear,
     ship's wheel, ferris wheel and sun all share; this treatment is
     structurally incapable of that reading regardless of paddle shape.

None of the three carve round holes anywhere - the r8 defect class is
closed off structurally, not patched in one variant only.

Ground: r7/r8's square-corner, gently-lightened flat field (see r7's
docstring for the sodium/lithium source numbers, unchanged this round).
Three colour families, chosen from the subject, not to dodge collision
(LOGO_PLAYBOOK 2026-07-30 ruling): water blue (the wheel's medium),
wheat gold (the grain pile's own colour), clay terracotta (millwork
stone/timber). Six candidates = 3 wheel treatments crossed unevenly
across the 3 colours, never the same treatment recoloured twice in a
row with nothing else changed.

Visual weight is kept on the pile, not the wheel, per kura's own stated
reason for picking r8's waterwheel over the other seven ("戻ったら成果
が溜まっていた", not "機械が回っていた"): wheel kept small, pile widened
and pulled taller, and the wheel/pile ink-pixel ratio is measured per
candidate below rather than asserted.

SS=4: 2048px composite -> 512 LANCZOS. No vanilla texture pasted.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r9"
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


def paddle_poly(
    cx: float, cy: float, ang_deg: float, r_in: float, blade_len: float, blade_w: float
) -> list[tuple[float, float]]:
    """Elongated paddle board (length:width ~3.5:1). This round's first
    pass made these short and near-square (~1:1) on the theory that
    elongation was what caused the sun-reading; rendered and looked at
    plainly, that instead reads as a flower/cog - short, flat-topped,
    evenly-spaced teeth are literally the gear grammar (yacl's own icon
    uses exactly that). Elongated boards are restored here; what
    actually keeps a radial mark from reading as a gear/sun/ship's-wheel
    /ferris-wheel is never letting it be a *full, free-floating 360deg
    symmetric ring* in the first place - see the three treatments below,
    all of which break that symmetry structurally rather than through
    paddle proportion."""
    local = [
        (r_in, -blade_w / 2),
        (r_in + blade_len, -blade_w / 2),
        (r_in + blade_len, blade_w / 2),
        (r_in, blade_w / 2),
    ]
    pts = []
    for lx, ly in local:
        wx, wy = cx + lx, cy + ly
        wx, wy = rot((wx, wy), (cx, cy), ang_deg)
        pts.append(P(wx, wy))
    return pts


def scoop_paddle(
    d: ImageDraw.ImageDraw,
    cx: float,
    cy: float,
    ang_deg: float,
    r_in: float,
    r_out: float,
    arc_deg: float,
) -> None:
    """Curved bucket/scoop (an overshot-wheel paddle), not a straight
    board. paddle_poly's straight rectangles kept reading as sun rays no
    matter how the count or arc-coverage was varied - a ray is defined
    by being a straight line from a hub, and no amount of shortening or
    partial-arc placement removes the straightness. A scoop is curved
    (a wedge of an annulus, drawn as two concentric pie-slices - the
    inner one erased back to transparent rather than filled with the
    background colour, so it composites cleanly against any field
    colour including the housing/water shapes it sits next to), which
    is a different enough silhouette family that it stopped triggering
    the sun/ray reading in the same visual check that caught the
    straight-board version."""
    a0, a1 = ang_deg - arc_deg / 2, ang_deg + arc_deg / 2
    d.pieslice(
        [*P(cx - r_out, cy - r_out), *P(cx + r_out, cy + r_out)], a0, a1, fill=WHITE
    )
    d.pieslice(
        [*P(cx - r_in, cy - r_in), *P(cx + r_in, cy + r_in)],
        a0 - 6,
        a1 + 6,
        fill=(0, 0, 0, 0),
    )


# ============================================================== wheels ===
# Three treatments, none of them a full free-floating 360deg-symmetric
# ring of paddles - that specific silhouette grammar is what a gear, a
# ship's wheel, a ferris wheel and a sun all share, regardless of how
# the individual tooth/paddle/ray is shaped. Each treatment below is
# asymmetric or occluded by construction, not by a proportion tweak.


def wheel_arc(d: ImageDraw.ImageDraw, cx: float, cy: float, r: float) -> None:
    """Treatment A. Three earlier passes on this treatment are kept as
    documented dead ends rather than silently overwritten:

    1. Thin radial paddles on a partial arc, plus a slim axle post: read
       as "sun peeking out, with a flag on top". A thin free-floating
       support is not enough occluding mass to kill the sun-reading.
    2. A solid triangular A-frame stand *beside* the wheel: read as a
       satellite dish on a tripod. Adjacent mass does not help if it
       never overlaps the circle's own silhouette.
    3. A solid rectangular block *below* the wheel, overlapping up into
       its lower third, paddles on the upper arc only: read as a sun
       rising behind a building - *worse* than pass 1. A horizontal
       (top-visible / bottom-hidden) split is the sunrise convention
       itself, regardless of what hides the bottom half.

    Treatment B's housing block is the one construction that read
    correctly on first check, and the one structural fact that
    distinguishes it from all three dead ends above is that its cut is
    *vertical* (left visible / right hidden), never horizontal. This
    treatment reuses that same vertical logic, mirrored - housing on the
    left, paddles visible on the right-facing arc, angled toward the
    pile as if this is the wheel's working side."""
    hub_r = r * 0.58
    for ang in (-64, -32, 0, 32, 64):  # right-facing arc, toward the pile
        scoop_paddle(d, cx, cy, ang, hub_r * 0.88, r * 1.05, 26)
    d.ellipse([*P(cx - hub_r, cy - hub_r), *P(cx + hub_r, cy + hub_r)], fill=WHITE)
    # housing block, left side, overlapping the hub - vertical cut, not horizontal
    d.rectangle(
        [*P(cx - r * 0.85, cy - r * 0.95), *P(cx + 0.01, cy + r * 1.05)], fill=WHITE
    )


def wheel_housed(d: ImageDraw.ImageDraw, cx: float, cy: float, r: float) -> None:
    """Treatment B: full paddle ring, but half of it sits behind a solid
    mill-housing block, so only the paddles on the visible (left) side
    ever reach the silhouette - the housing occludes the symmetry rather
    than the paddle geometry avoiding it, and doubles as a second
    concrete object (a mill wall) reinforcing "machine", not "mark"."""
    hub_r = r * 0.58
    for ang in (144, 176, 208, 240, 272):  # left-facing arc only
        scoop_paddle(d, cx, cy, ang, hub_r * 0.88, r * 1.05, 26)
    d.ellipse([*P(cx - hub_r, cy - hub_r), *P(cx + hub_r, cy + hub_r)], fill=WHITE)
    # housing block, right side, overlapping the hub - reads as a wall
    # the near half of the wheel disappears behind
    d.rectangle(
        [*P(cx - 0.01, cy - r * 0.95), *P(cx + r * 0.85, cy + r * 1.05)], fill=WHITE
    )


def wheel_waterline(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, bg_mid: Color
) -> None:
    """Treatment C. Three earlier passes on this treatment are kept as
    documented dead ends:

    1. A full paddle ring with a flat horizontal crop at a "waterline".
       A circle with rays cut off flat along the bottom is a
       sunrise-over-a-horizon - a *stronger* sun-reading, not a weaker
       one; a straight crop reads as ground level, and a notched disc
       sitting on ground level is the sunrise convention itself.
    2. A rectangular stone plinth placed *under* the wheel, not
       overlapping it: read as a sun over a brick wall.
    3. A wavy body of water rising up into the *lower* third of the
       wheel (three soft crests): still read as a sunrise, because the
       cut was still fundamentally horizontal (top visible / bottom
       hidden) even with a wavy edge instead of a straight one.

    What treatment A/B established: only a *vertical* cut (left visible/
    right hidden, or the reverse) avoids the sunrise convention, because
    that split is not a silhouette any sun/sunrise icon uses. This water
    body is therefore oriented the same way - filling in from the right,
    its boundary wavy but predominantly vertical (x wobbles as y
    increases, not the other way round) - paddles visible on the
    left-facing arc, mirrored from treatment A's right-facing one."""
    hub_r = r * 0.56
    for ang in (124, 156, 188, 220, 252):  # left-facing arc, clear of the water
        scoop_paddle(d, cx, cy, ang, hub_r * 0.88, r * 1.02, 26)
    d.ellipse([*P(cx - hub_r, cy - hub_r), *P(cx + hub_r, cy + hub_r)], fill=WHITE)

    water_left_x = cx + hub_r * 0.05
    pts = [P(cx + r * 1.05, cy - r * 1.05), P(water_left_x, cy - r * 1.05)]
    for i in range(9):
        t = i / 8
        y = cy - r * 1.05 + r * 2.10 * t
        xx = water_left_x + math.sin(t * math.pi * 2.5) * hub_r * 0.20
        pts.append(P(xx, y))
    pts += [P(water_left_x, cy + r * 1.05), P(cx + r * 1.05, cy + r * 1.05)]
    d.polygon(pts, fill=WHITE)
    for dx in (hub_r * 0.35, hub_r * 0.65):
        d.line(
            [P(water_left_x + dx, cy - r * 0.80), P(water_left_x + dx, cy + r * 0.80)],
            fill=bg_mid,
            width=int(r * 0.035 * SIDE),
        )


def wheel_plain(
    d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, bg_mid: Color
) -> None:
    """Treatment D - the reliability fallback, included alongside A/B/C
    rather than in place of them. A plain disc has no radiating elements
    at all, so it structurally cannot read as a gear, ship's wheel,
    ferris wheel or sun regardless of viewing size - there is nothing
    for those readings to attach to. What carries "waterwheel" here is
    entirely the scene: an axle post to a support beam above, and a
    bold, unmissable double-line stream crossing the disc's lower third
    (thicker than A/B/C's texture ripples, because with no paddles to
    help, the water has to do more of the identifying work on its own)."""
    hub_r = r * 0.62
    top_y = cy - r * 1.25
    d.rectangle([*P(cx - 0.012, top_y), *P(cx + 0.012, cy - hub_r * 0.2)], fill=WHITE)
    d.rectangle(
        [*P(cx - r * 0.55, top_y - 0.02), *P(cx + r * 0.55, top_y + 0.02)], fill=WHITE
    )
    d.ellipse([*P(cx - hub_r, cy - hub_r), *P(cx + hub_r, cy + hub_r)], fill=WHITE)
    y0 = cy + hub_r * 0.28
    for i, yy in enumerate((y0, y0 + hub_r * 0.42)):
        pts = []
        for j in range(11):
            t = j / 10
            x = cx - hub_r * 1.35 + hub_r * 2.7 * t
            wob = yy + math.sin(t * math.pi * 2.5 + i) * hub_r * 0.12
            pts.append(P(x, wob))
        d.line(pts, fill=bg_mid, width=int(hub_r * 0.16 * SIDE), joint="curve")


# ================================================================ pile ===


def draw_pile(
    d: ImageDraw.ImageDraw,
    base_y: float,
    cx: float,
    width: float,
    height: float,
    n_kernels: int = 2,
) -> None:
    """Wide, dominant mound - kept the visual centre of gravity per
    kura's own stated reason for picking this candidate over the other
    seven in round 8."""
    hw = width / 2
    mound = [
        P(cx - hw, base_y),
        P(cx - hw * 0.62, base_y - height * 0.62),
        P(cx - hw * 0.18, base_y - height),
        P(cx + hw * 0.20, base_y - height * 0.94),
        P(cx + hw * 0.64, base_y - height * 0.58),
        P(cx + hw, base_y),
    ]
    d.polygon(mound, fill=WHITE)
    for i in range(n_kernels):
        kx = cx - hw * 0.12 + i * hw * 0.28
        ky = base_y - height * 1.02
        d.ellipse([*P(kx - 0.022, ky - 0.022), *P(kx + 0.022, ky + 0.022)], fill=WHITE)


# =========================================================== candidates ===

WATER = ((44, 128, 198), (92, 166, 224))  # H208 S78% V78%
WHEAT = ((214, 160, 46), (228, 190, 108))  # H42 S79% V84%
CLAY = ((198, 92, 52), (216, 130, 92))  # H16 S74% V78%

WHEEL_CX, WHEEL_CY, WHEEL_R = 0.33, 0.335, 0.175


def build(wheel_fn, colours, needs_bg: bool = False):
    top_rgb, bot_rgb = colours
    field = ground(top_rgb, bot_rgb)
    bg_mid = mid(top_rgb, bot_rgb)

    wheel_layer, dw = canvas()
    pile_layer, dp = canvas()

    if needs_bg:
        wheel_fn(dw, WHEEL_CX, WHEEL_CY, WHEEL_R, bg_mid)
    else:
        wheel_fn(dw, WHEEL_CX, WHEEL_CY, WHEEL_R)
    draw_pile(dp, base_y=0.87, cx=0.56, width=0.64, height=0.42)

    composed = field.copy()
    composed.alpha_composite(pile_layer)
    composed.alpha_composite(wheel_layer)

    return finish(composed), top_rgb, wheel_layer, pile_layer


def ink(img: Image.Image) -> int:
    return int((np.array(img.split()[3]) > 10).sum())


CANDIDATES = {
    "A_arc_blue": lambda: build(wheel_arc, WATER),
    "B_housed_wheat": lambda: build(wheel_housed, WHEAT),
    "B_housed_clay": lambda: build(wheel_housed, CLAY),
    "C_waterline_clay": lambda: build(wheel_waterline, CLAY, needs_bg=True),
    "D_plain_blue": lambda: build(wheel_plain, WATER, needs_bg=True),
    "D_plain_wheat": lambda: build(wheel_plain, WHEAT, needs_bg=True),
}


def main() -> None:
    print(f"{'name':<20} {'wheel px':>10} {'pile px':>10} {'wheel%':>8}")
    for name, fn in CANDIDATES.items():
        img, _top, wheel_layer, pile_layer = fn()
        img.save(OUT_DIR / f"{name}_512.png")
        w_ink, p_ink = ink(wheel_layer), ink(pile_layer)
        print(f"{name:<20} {w_ink:>10} {p_ink:>10} {w_ink/(w_ink+p_ink)*100:>7.1f}%")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
