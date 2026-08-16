# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 7 candidates.

Round 1-6 verdict history (see r6's own docstring for the full round-6
account): six rounds landed on either "vanilla screenshot" (furnace,
hopper - r3) or "abstract geometry with no concrete referent" (arrows -
r4; diamonds, brackets, flame - r5/r6). Neither is the group the seven
named-good icons belong to.

This round targets the third, never-tried group explicitly named in the
2026-08-15 kura ruling (LOGO_PLAYBOOK "kura の合否基準"): sodium /
lithium / architectury-api / immediatelyfast / iris / reeses / yacl are
none of them a vanilla screenshot and none of them an arbitrary abstract
mark - each is a *concrete, everyday object* (a water drop, a feather, a
construction crane, an upward flourish, a colour wheel, a set of option
sliders, a gear) redrawn as a single clean flat silhouette on a solid
saturated field. That is the group this round draws from.

Structural targets, measured directly off the two icons kura named first
(sodium, lithium - see measure_refs.py, run against the local originals
in branding/gate0/icons/, not against the LOGO_PLAYBOOK table, which
flags its own bbox/coverage numbers as measurement-method-dependent and
its rounded-corner figure as unverified against the source assets):

    sodium (96x96, water droplet, crescent notch top):
      ground top->bottom  H108 S55->43% V76->80%  (#6DC257 -> #87CD75,
        a very slight lightening toward the bottom - not documented in
        the playbook's "flat single colour" description, confirmed by
        direct per-row sampling, see measure_refs.py output)
      corner radius        0px - square to the pixel edge on all four
        corners (135,206,117,255 opaque at (95,95)). The playbook's
        "~18-22% radius, sodium/lithium type" does not match either
        source file directly measured; rounding is very likely applied
        by the store UI's own CSS mask, not baked into the icon. Ground
        below is drawn square for the same reason - baking in a radius
        the platform reapplies would double-crop the corners.
      glyph colour          pure #FFFFFF (mode colour of glyph pixels)
      glyph bbox            100% width x 78% height (drop bleeds to
        left/right/bottom edges, 22% margin only at the top)
      glyph pixel ratio     30.9% of the frame
      connected components  2

    lithium (96x96, feather, quill notch):
      ground top->bottom  H262 S54->44% V75->78%  (#7E59C0 -> #8E6FC7,
        same lightening pattern as sodium - not a one-off)
      corner radius         0px, same as sodium
      glyph colour          #EEEAF4 - NOT pure white. A pale lavender-
        tinted off-white, distinct from sodium's pure white. Contradicts
        the playbook's flat claim "グリフは実質 #FFFFFF" for this
        specific source file; sodium is white, lithium is not.
      glyph bbox             50% width x 50% height, centred (25% margin
        on all four sides, symmetric to within 1px)
      glyph pixel ratio      12.3% of the frame
      connected components   1

Design targets taken from the above (this round's "measured, then drawn
to the number" requirement): ground is a flat colour field with a
gentle vertical lightening (~10-14pt S drop, ~4-5pt V rise, top to
bottom), corners square, glyph pure white (all nine candidates use
white uniformly rather than mixing in lithium's off-white tint, for set
consistency - a deliberate deviation from lithium's own measurement,
noted rather than silently dropped). Each candidate's own bbox/coverage
is measured after drawing with the same method as the reference scan
(measure_refs.py's analyze()) and reported against a per-subject target
picked between the two anchors' 25%-78% bbox range depending on the
subject's natural aspect ratio, rather than a single fixed number for
all nine.

Nine subjects, six-plus distinct (LOGO_PLAYBOOK "9案は主題6-9種類で
振ってください" - no colour variants of one theme): hourglass, twin
sprout, crescent moon, pendulum, wind-up key + spring, beehive, clam +
pearl, cocoon on a twig, book + bookmark ribbon. Colour picked from each
subject's own material (glass/brass amber, leaf green, night indigo,
clock brass, copper, honey amber, sea teal, dusty rose, wine leather) -
not steered away from any of the 33 reference icons' colours (kura
2026-07-30 ruling in LOGO_PLAYBOOK "色の選び方": don't dodge collision on
colour, the subject decides the colour).

Banned motifs avoided (LOGO_PLAYBOOK "使用禁止" list, all rounds to
date): furnace front, hopper, plain right-arrow, raw->cooked pair,
vanilla clock face/hands, strata swatch, chunk grid, diamond pair,
flame/embers, bracket/ring/annulus as the WHOLE mark, world diorama,
spyglass, pouch, face, text/wordmark/monogram. The wind-up key's handle
is a solid flat tab, not a ring, specifically to stay clear of the ring
ban even as a sub-detail of a larger concrete object.

Motif collision against the 33 local reference icons (branding/gate0/
icons/*_orig.*, contact-sheet reviewed directly, not by filename guess):
none of the nine subjects below appear among them. Closest neighbours
are sodium/sodium-extra's white droplet-ish notch shape (organic blob,
not a leaf/sprout) and yacl's flower-cog (a full ring of teeth, avoided
outright by dropping "gear" from this round's subject list per the
prompt's own caution that yacl already owns it).

SS=4: 2048px composite -> 512 LANCZOS. All glyphs are flat filled
polygons/ellipses (no vanilla texture crop this round - these are not
vanilla items), carved with background-coloured cutouts for interior
detail (sand-line, coil bands, entrance hole, spine crease, ribbon slot)
the same way sodium's own crescent notch is a coloured cutout, not a
separate stroke.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r7"
OUT_DIR.mkdir(exist_ok=True)

SIDE = 2048  # SS=4 composite size
OUT = 512

Color = tuple[int, int, int, int]


# ============================================================== geometry ===


def P(fx: float, fy: float) -> tuple[float, float]:
    """Fractional (0..1) canvas coordinate -> pixel coordinate."""
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
    """Vesica/leaf shape: pointed lens between base and tip (fractions),
    max half-width `bulge` (fraction of SIDE) at the midpoint, tapering
    to a point at both ends via a sine profile."""
    bx, by = base
    tx, ty = tip
    dx, dy = tx - bx, ty - by
    length = math.hypot(dx, dy)
    if length == 0:
        return [P(bx, by)]
    ux, uy = dx / length, dy / length  # along axis
    px, py = -uy, ux  # perpendicular

    pts_fwd = []
    pts_back = []
    for i in range(n + 1):
        t = i / n
        cx = bx + dx * t
        cy = by + dy * t
        w = bulge * math.sin(math.pi * t)
        pts_fwd.append((cx + px * w, cy + py * w))
        pts_back.append((cx - px * w, cy - py * w))
    pts = pts_fwd + pts_back[::-1]
    return [P(x, y) for x, y in pts]


def spiral_points(
    center: tuple[float, float], r0: float, r1: float, turns: float, n: int = 80
) -> list[tuple[float, float]]:
    cx, cy = center
    pts = []
    for i in range(n + 1):
        t = i / n
        r = r0 + (r1 - r0) * t
        a = t * turns * 2 * math.pi
        pts.append(P(cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def ground(
    top_rgb: tuple[int, int, int], bottom_rgb: tuple[int, int, int]
) -> Image.Image:
    """Flat field, square corners (measured off sodium/lithium - see
    module docstring), with the gentle vertical lightening both
    reference icons carry."""
    rows = np.linspace(0, 1, SIDE)[:, None]
    top = np.array(top_rgb, dtype=np.float32)
    bot = np.array(bottom_rgb, dtype=np.float32)
    grad = top[None, :] + (bot - top[None, :]) * rows  # (SIDE, 3)
    arr = np.repeat(grad[:, None, :], SIDE, axis=1).astype(np.uint8)
    alpha = np.full((SIDE, SIDE, 1), 255, dtype=np.uint8)
    return Image.fromarray(np.concatenate([arr, alpha], axis=2), "RGBA")


def composite(field: Image.Image, glyph: Image.Image) -> Image.Image:
    out = field.copy()
    out.alpha_composite(glyph)
    return out


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


WHITE: Color = (255, 255, 255, 255)


# =================================================================== #1 ===


def cand_hourglass() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (224, 168, 64)  # warm amber glass/sand, H~38 S~71% V~88%
    bot_rgb = (232, 194, 128)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    outer = [
        P(0.22, 0.15),
        P(0.78, 0.15),
        P(0.585, 0.50),
        P(0.78, 0.85),
        P(0.22, 0.85),
        P(0.415, 0.50),
    ]
    d.polygon(outer, fill=WHITE)
    # end caps (wood/metal bars)
    d.rounded_rectangle(
        [*P(0.16, 0.12), *P(0.84, 0.165)], radius=int(0.02 * SIDE), fill=WHITE
    )
    d.rounded_rectangle(
        [*P(0.16, 0.835), *P(0.84, 0.88)], radius=int(0.02 * SIDE), fill=WHITE
    )

    # hollow the glass cavity (bg colour), inset from the outer bowtie
    bg_mid = tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)
    inner = [
        P(0.275, 0.205),
        P(0.725, 0.205),
        P(0.565, 0.50),
        P(0.725, 0.795),
        P(0.275, 0.795),
        P(0.435, 0.50),
    ]
    d.polygon(inner, fill=bg_mid)

    # settled sand: refill the bottom half of the cavity white, plus a
    # thin sand-fall thread through the neck
    sand = [
        P(0.30, 0.60),
        P(0.70, 0.60),
        P(0.725, 0.795),
        P(0.275, 0.795),
    ]
    d.polygon(sand, fill=WHITE)
    d.line([P(0.5, 0.50), P(0.5, 0.60)], fill=WHITE, width=int(0.012 * SIDE))

    return composite(field, glyph), top_rgb


# =================================================================== #2 ===


def cand_sprout() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (122, 196, 96)  # fresh spring green, H~103 S~51% V~77%
    bot_rgb = (156, 214, 132)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    # stem, gentle lean
    stem = [
        P(0.485, 0.86),
        P(0.515, 0.86),
        P(0.535, 0.50),
        P(0.505, 0.42),
        P(0.475, 0.50),
    ]
    d.polygon(stem, fill=WHITE)

    # two leaves branching from the stem tip
    d.polygon(leaf_polygon((0.505, 0.46), (0.28, 0.20), 0.115), fill=WHITE)
    d.polygon(leaf_polygon((0.505, 0.44), (0.74, 0.16), 0.13), fill=WHITE)

    # soil line the stem rises from
    d.ellipse([*P(0.30, 0.845), *P(0.70, 0.90)], fill=WHITE)

    return composite(field, glyph), top_rgb


# =================================================================== #3 ===


def cand_moon() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (42, 46, 92)  # night indigo, thematically required (moon)
    bot_rgb = (64, 70, 122)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    d.ellipse([*P(0.24, 0.24), *P(0.76, 0.76)], fill=WHITE)
    bg_mid = tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)
    d.ellipse([*P(0.36, 0.20), *P(0.88, 0.72)], fill=bg_mid)

    return composite(field, glyph), top_rgb


# =================================================================== #4 ===


def cand_pendulum() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (198, 150, 62)  # clock brass, H~36 S~69% V~78%
    bot_rgb = (214, 178, 106)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    # mount bracket
    d.rounded_rectangle(
        [*P(0.40, 0.135), *P(0.60, 0.185)], radius=int(0.02 * SIDE), fill=WHITE
    )
    pivot = (0.50, 0.19)
    bob = (0.665, 0.79)
    d.line([P(*pivot), P(*bob)], fill=WHITE, width=int(0.028 * SIDE), joint="curve")
    d.ellipse(
        [*P(bob[0] - 0.145, bob[1] - 0.145), *P(bob[0] + 0.145, bob[1] + 0.145)],
        fill=WHITE,
    )
    d.ellipse(
        [
            *P(pivot[0] - 0.028, pivot[1] - 0.028),
            *P(pivot[0] + 0.028, pivot[1] + 0.028),
        ],
        fill=WHITE,
    )

    return composite(field, glyph), top_rgb


# =================================================================== #5 ===


def cand_key() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (214, 110, 48)  # copper, H~19 S~78% V~84%
    bot_rgb = (228, 148, 96)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    # solid wing tab (not a ring - stays clear of the ring ban)
    d.polygon(leaf_polygon((0.50, 0.34), (0.50, 0.10), 0.16, n=20), fill=WHITE)
    # shaft
    d.rectangle([*P(0.465, 0.32), *P(0.535, 0.52)], fill=WHITE)
    # spiral spring, thick stroke
    spts = spiral_points((0.50, 0.66), 0.02, 0.20, turns=2.6, n=90)
    d.line(spts, fill=WHITE, width=int(0.024 * SIDE), joint="curve")

    return composite(field, glyph), top_rgb


# =================================================================== #6 ===


def cand_beehive() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (232, 168, 40)  # honey gold, H~40 S~83% V~91%
    bot_rgb = (240, 198, 96)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    dome = [
        P(0.50, 0.14),
        P(0.735, 0.30),
        P(0.80, 0.52),
        P(0.775, 0.72),
        P(0.70, 0.85),
        P(0.30, 0.85),
        P(0.225, 0.72),
        P(0.20, 0.52),
        P(0.265, 0.30),
    ]
    d.polygon(dome, fill=WHITE)

    bg_mid = tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)
    for y in (0.40, 0.55, 0.70):
        d.arc(
            [*P(0.20, y - 0.09), *P(0.80, y + 0.09)],
            start=15,
            end=165,
            fill=bg_mid,
            width=int(0.022 * SIDE),
        )
    d.ellipse([*P(0.435, 0.735), *P(0.565, 0.85)], fill=bg_mid)

    return composite(field, glyph), top_rgb


# =================================================================== #7 ===


def cand_shell() -> tuple[Image.Image, tuple[int, int, int]]:
    """Two circular-sector valves sharing one hinge vertex (a pie-slice
    pair, not the leaf helper - the leaf-polygon version fused into one
    unreadable blob because both bulges overlapped near the hinge; a
    shared-vertex wedge pair keeps the gap between them a true zero at
    the hinge and widening outward, i.e. an actually-ajar shell)."""
    top_rgb = (46, 168, 172)  # sea teal, H~178 S~73% V~66%
    bot_rgb = (86, 196, 198)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    hinge = (0.16, 0.50)
    r = 0.58
    bbox = [*P(hinge[0] - r, hinge[1] - r), *P(hinge[0] + r, hinge[1] + r)]
    d.pieslice(bbox, 360 - 46, 360 - 5, fill=WHITE)
    d.pieslice(bbox, 5, 46, fill=WHITE)

    # pearl, sitting solid in the gap - no ring, no outline
    pr = 0.030
    px = hinge[0] + 0.42
    py = hinge[1]
    d.ellipse([*P(px - pr, py - pr), *P(px + pr, py + pr)], fill=WHITE)

    return composite(field, glyph), top_rgb


# =================================================================== #8 ===


def cand_cocoon() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (196, 132, 152)  # dusty rose, H~340 S~33% V~77%
    bot_rgb = (214, 164, 180)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    d.line(
        [P(0.18, 0.18), P(0.82, 0.24)],
        fill=WHITE,
        width=int(0.018 * SIDE),
        joint="curve",
    )
    d.line([P(0.50, 0.215), P(0.50, 0.34)], fill=WHITE, width=int(0.014 * SIDE))
    d.polygon(leaf_polygon((0.50, 0.32), (0.50, 0.86), 0.185, n=28), fill=WHITE)

    bg_mid = tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)
    for y in (0.46, 0.60, 0.74):
        d.line([P(0.335, y), P(0.665, y)], fill=bg_mid, width=int(0.018 * SIDE))

    return composite(field, glyph), top_rgb


# =================================================================== #9 ===


def cand_book() -> tuple[Image.Image, tuple[int, int, int]]:
    top_rgb = (128, 34, 46)  # wine leather, H~352 S~73% V~50%
    bot_rgb = (162, 62, 76)
    field = ground(top_rgb, bot_rgb)
    glyph, d = canvas()

    # ribbon tail (drawn first, sits "under" the book, only the part
    # below the bottom edge stays visible)
    d.polygon(
        [
            P(0.60, 0.18),
            P(0.665, 0.18),
            P(0.665, 0.895),
            P(0.6325, 0.845),
            P(0.60, 0.895),
        ],
        fill=WHITE,
    )

    d.rounded_rectangle(
        [*P(0.22, 0.16), *P(0.78, 0.84)], radius=int(0.025 * SIDE), fill=WHITE
    )

    bg_mid = tuple(int((a + b) / 2) for a, b in zip(top_rgb, bot_rgb)) + (255,)
    d.rectangle([*P(0.335, 0.16), *P(0.365, 0.84)], fill=bg_mid)

    return composite(field, glyph), top_rgb


CANDIDATES = {
    "hourglass": cand_hourglass,
    "sprout": cand_sprout,
    "moon": cand_moon,
    "pendulum": cand_pendulum,
    "key": cand_key,
    "beehive": cand_beehive,
    "shell": cand_shell,
    "cocoon": cand_cocoon,
    "book": cand_book,
}


def main() -> None:
    for name, fn in CANDIDATES.items():
        img, _top = fn()
        finish(img).save(OUT_DIR / f"{name}_512.png")
    print(f"wrote {len(CANDIDATES)} candidates to {OUT_DIR}")


if __name__ == "__main__":
    main()
