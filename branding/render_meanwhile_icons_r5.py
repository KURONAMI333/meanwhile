# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 5 candidates.

Rounds 1-4 were all rejected. This round starts from kura naming actual
shipped icons to extract structure from, split into two anchor groups
(LOGO_PLAYBOOK's implicit "function-guessable" vs "brand mark" registers,
made explicit here because kura pointed at named examples of each):

  group 1 (function can be guessed): appleskin, the-block-keeps-ticking,
    unloaded-activity, xaeros-minimap, xaeros-world-map
  group 2 (brand mark, measured numerically per LOGO_PLAYBOOK's
    "simpler/more complex" protocol): architectury-api, immediatelyfast,
    iris, lithium, reeses-sodium-options, sodium, yacl

Banned anchors (chunk-loader-persistence, copper-chunk-loader) are both
literal in-world screenshots: a block/portal rendered inside its terrain
context (grass, sky, ground). That is the specific failure - not "3D
render" in general (the-block-keeps-ticking is a staged, isolated render
on a flat vignette and is a *positive* anchor) but "the game world itself
as backdrop", which reads as a diorama/screenshot rather than a mark.

What round 3 and round 4 already used up, and this round does not reuse:
  - a single vanilla machine shown front-on as a portrait (furnace, hopper)
    - kura's verdict was that the whole register ("machine portrait") does
      not communicate the mod's function, not just the choice of machine
  - a plain right-pointing arrow as the glyph
  - a raw -> cooked / ore -> ingot before/after transformation pair
  - a clock face (dial, hands)

Group 1 candidates (function-guessable, one device borrowed from a named
anchor each, applied to a subject this mod has not shown before):

  A_cutaway   appleskin's device: cut into something ordinary and show the
              hidden interior state. Applied to a slice of ground (dirt
              over stone, real texture crops) with a corner notched out
              Minecraft-blocky-style (a stairstep, not a round bite -
              round has no vanilla precedent) revealing a lit redstone
              torch still glowing inside, unseen from outside.
  B_chunkgrid xaeros's device: the icon IS the product's own subject
              matter (a map mod's icon is a map). Applied here as a small
              grid of chunk swatches - most idle grey/brown, one far from
              centre still carrying a lit redstone spark, i.e. the actual
              thing this mod's tests watch for (a chunk you cannot see
              still being live).
  C_split     unloaded-activity's device: a two-tone split panel. Reused
              for what the *name* "meanwhile" means (elsewhere, at the
              same time) rather than round 4's before/after: both halves
              carry the identical spark at the identical position, one
              dim (away) and one lit (here) - simultaneity, not a
              transformation, so there is no arrow connecting them.

Group 2 candidates (brand mark - single glyph, flat field, high bbox,
few connected components, per the numeric anchors measured below):

  D_wordmark  round 2's D_letters direction (kura: has potential in the
              branding register, was judged in the wrong frame at the
              time), rebuilt at anchor strength: tighter kerning, heavier
              stroke (scale 5 vs round 2's 4), bbox pushed to ~98% on the
              long line (matches iris 99% / sodium 100%), saturated
              copper field instead of flat cream, cream ink instead of
              near-black (round 2's own metrics: bbox 99% but coverage
              only 16% - thin for the bbox it claimed; this round's target
              is the anchor coverage band, roughly 25-45%).
  E_bracket   nothing round 1-4 tried: a typographic mark, not a glyph
              drawn from a physical object, sidestepping negative_list's
              "real object silhouette from memory" rule entirely. A bold
              pixel parenthesis pair, "(...)" - the mark for an aside
              happening at the same time as the main clause, which is
              literally what "meanwhile" is grammatically for.
  F_echo      (coordinator round replacement for F_ember, see addendum
              below) two diamonds offset diagonally, one dim and one
              bright - an echo, not an object, so it carries no motif
              collision with the flame/water/lightning marks already used
              in this genre.

Numeric anchor measurements this round's group 2 targets were pulled from
(2026-08-16, corner-flood-fill bg detection, 4-connectivity components,
threshold 30/255 on colour distance from bg; see analyze_icon.py in the
scratchpad for the method):

  architectury-api   bg (0,0,0)       bbox 83.3%  fg 52.8%  components 1  line-width-ratio 2.07
  immediatelyfast    bg (39,41,43)    bbox 91.7%  fg 18.9%  components 3  line-width-ratio 3.89
  iris               bg (255,255,255) bbox 99.0%  fg 48.9%  components 1  line-width-ratio 2.55
  lithium            bg (142,111,199) bbox 51.0%* fg 12.5%* components 1  line-width-ratio 2.37
  reeses-sodium-opts bg (35,11,66)    bbox 86.3%  fg 35.8%  components 3  line-width-ratio 1.01
  sodium             bg (109,194,87) bbox 100.0%  fg 45.1%  components 1  line-width-ratio 2.73
  yacl               bg (35,35,35)    bbox 75.0%  fg 29.2%  components 1  line-width-ratio 2.26

  (*lithium's field has a subtle gradient the corner-sample method reads
  unevenly; treat its two numbers as noisy, everything else is a flat
  single-colour field and reads clean)

Read plainly: single-digit connected components, bbox in the 75-100%
band except lithium's outlier, and coverage mostly 30-53% except
immediatelyfast's thin arrow (19%) and lithium's likely-undercounted 12%.
This round's group 2 candidates are built against that band, not against
round 2's own numbers (bbox 99% / coverage 16%), which sit on the bbox
side of the anchor range but well under the coverage side.

Colour is taken from the subject (LOGO_PLAYBOOK's 2026-07-30 addendum),
sampled from real vanilla texture averages rather than picked freestyle:
copper_block.png for D (a worn-copper amber - the "sat untouched, aged"
association is on-theme, not incidental), lapis_block.png for E (a
distinct hue from every other candidate here, and lapis is the vanilla
material for enchantment/passive calculation), redstone_block.png /
redstone_torch.png for A/C/F (the "still active, glowing, unseen"
material). B's field is a plain dark neutral so the grid itself reads as
the subject.

Every Minecraft *object* below (dirt, stone, redstone torch, redstone
dust) is real vanilla pixels, integer-NEAREST placed, per LOGO_PLAYBOOK's
standing rule and the failure round 3 already paid for once (a hand-drawn
furnace read as a hearth, not a furnace). The wordmark and bracket glyphs
in group 2 are typographic, not objects, so that rule does not apply to
them - same footing as the group 2 anchors' own glyphs, none of which are
literal vanilla textures either.

SS=4 (2048px composite -> 512 LANCZOS). Pixel-art elements placed at an
integer NEAREST factor before the final reduction.

---

Coordinator round (2026-08-16, same session, three fixes to the six
candidates above - no new candidate families, per the coordinator's
explicit "新しい族は要りません"):

1. D_wordmark was clipped. On the 512px tile, WHILE's leading W and
   trailing E were both cut by the field's own rounded corner - the line
   was 125 of 128 grid units wide (1.5-unit margin), and the corner radius
   (20% of the side = 25.6 units) needs roughly 7.5 units of margin for a
   rectangle's corner to stay inside the curve. Invisible at 48/64px,
   where the same cut and the downsample blur landed on top of each other.
   Fixed by a search (cand_wordmark, below) that renders at decreasing
   scale and keeps the first value verified - by counting ink pixels
   before and after the field's alpha mask, not by trusting the geometry
   math alone - to clip zero pixels.

2. F_ember was dropped. Opened branding/gate0/icons/sodium-extra_orig.webp
   on the coordinator's instruction: a yellow flat flame/teardrop glyph on
   white, single shape, flat field - structurally the same mark as
   F_ember, recoloured. sodium (green+white) is a second flame anchor in
   the same 7-icon group 2 set. A third flame reads as "another
   performance-mod flame" on the shelf, which this mod cannot say (it is
   not a performance mod - stated as a constraint from round 3 onward).
   Replaced with F_echo: two diamonds, offset diagonally, back one dim and
   front one bright - a typographic/graphic device with no physical
   referent, same footing as E_bracket.

3. B_chunkgrid's accent cell did not survive 48px. The lit cell was one of
   25 (5x5, each cell 15.6% of the frame) and carried only the fine
   redstone_dust_dot sprite as its marker - both the cell's share of the
   frame and the sprite's own detail were below the downsample's noise
   floor, so the 48px inset read as an unmarked grid. Dropped to 4x4 (each
   cell 26 units, larger without losing the "grid of chunks" reading) and
   added a bold flat diamond as the accent's primary shape, with the dust
   sprite layered on top for texture at 256/512 rather than carrying the
   read alone. A_cutaway had a milder version of the same problem (the
   torch read but was small relative to the frame) and got the same
   treatment: the window enlarged 5x7 -> 6x8 units and the torch overlay
   scale raised 54 -> 80.

Motif-collision check, all 33 gate0/icons/*_orig.* against all 6
candidates (not done before this round - see the report for the full
per-candidate table): F was the one confirmed collision (sodium,
sodium-extra - both fixed by the F_echo swap above). E_bracket's
self-flagged ring resemblance to yacl is corroborated by a second anchor,
chunk-loader (a purple ring/mandala with a black centre hole, same
"chunk-loader" genre) - left as a disclosed risk, not a scope item for
this pass. B_chunkgrid's grid-of-cells structure echoes
scripts-chunk-loaders (a 3x3 grid of blue cells) at the level of
composition, not colour or content - also left disclosed. A/C/D have no
match in the 33.
"""

from __future__ import annotations

import colorsys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- canvas ----

SS = 4
OUT = 512
SIDE = OUT * SS

GRID = 128  # design grid; 1 unit = SIDE/GRID px at SS
U = SIDE // GRID

BRANDING = Path(__file__).resolve().parent
CAND = BRANDING / "icon-candidates-r5"
VANILLA = (
    BRANDING
    / ".."
    / ".."
    / "_tools"
    / "MineTexture"
    / "tool_data"
    / "cache"
    / "vanilla_textures"
).resolve()

Color = tuple[int, int, int, int]


def hexc(h: str, a: int = 255) -> Color:
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1: Color, c2: Color, t: float) -> Color:
    return tuple(round(a + (b - a) * t) for a, b in zip(c1, c2))  # type: ignore[return-value]


def sample_avg(rel_path: str) -> Color:
    im = Image.open(VANILLA / rel_path).convert("RGB")
    px = list(im.getdata())
    n = len(px)
    r = sum(p[0] for p in px) / n
    g = sum(p[1] for p in px) / n
    b = sum(p[2] for p in px) / n
    return (round(r), round(g), round(b), 255)


def with_hsv(col: Color, s: float | None = None, v: float | None = None) -> Color:
    """Keep the hue of `col` (sampled from a real texture) but retarget
    saturation/value into the anchor band (LOGO_PLAYBOOK: S45-76% V78-100%
    for the bright-field+white-glyph register)."""
    h, s0, v0 = colorsys.rgb_to_hsv(col[0] / 255, col[1] / 255, col[2] / 255)
    r, g, b = colorsys.hsv_to_rgb(h, s0 if s is None else s, v0 if v is None else v)
    return (round(r * 255), round(g * 255), round(b * 255), 255)


BLACK = hexc("#000000")
WHITE = hexc("#FFFFFF")
CREAM = hexc("#F3E8CC")

COPPER = sample_avg("block/copper_block.png")  # (192,107,79)
LAPIS = sample_avg("block/lapis_block.png")  # (30,67,140)
REDSTONE = sample_avg("block/redstone_block.png")  # (175,24,5)
DIRT = sample_avg("block/dirt.png")  # (121,85,58)
STONE = sample_avg("block/stone.png")  # (143,143,143)

FIELD_D = with_hsv(COPPER, s=0.62, v=0.86)  # wordmark field
FIELD_E = with_hsv(LAPIS, s=0.60, v=0.62)  # bracket field
FIELD_F = mix(hexc("#141210"), REDSTONE, 0.06)  # echo field: near-black, barely warmed
FIELD_A = mix(DIRT, BLACK, 0.55)  # cutaway field: dirt, banked dark
FIELD_B = hexc("#1c2129")  # chunkgrid field: neutral dark slate
FIELD_C_DIM = mix(REDSTONE, BLACK, 0.62)
FIELD_C_LIT = with_hsv(REDSTONE, s=0.72, v=0.92)

ECHO_BACK = mix(REDSTONE, BLACK, 0.68)  # the ghost - dim, a beat behind
ECHO_FRONT = CREAM  # the real one - bright, caught up

# ------------------------------------------------------------- geometry ----


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def rect(
    d: ImageDraw.ImageDraw, x0: float, y0: float, x1: float, y1: float, col: Color
) -> None:
    d.rectangle([x0 * U, y0 * U, x1 * U - 1, y1 * U - 1], fill=col)


def poly(d: ImageDraw.ImageDraw, pts, col: Color) -> None:
    d.polygon([(x * U, y * U) for x, y in pts], fill=col)


def ground(col: Color, radius_pct: float = 0.20) -> Image.Image:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle(
        [0, 0, SIDE - 1, SIDE - 1], radius=int(SIDE * radius_pct), fill=col
    )
    return img


def on_field(scene: Image.Image, field: Image.Image) -> Image.Image:
    out = field.copy()
    empty = Image.new("RGBA", scene.size, (0, 0, 0, 0))
    out.alpha_composite(Image.composite(scene, empty, field.split()[3]))
    return out


def split_field(left: Color, right: Color, radius_pct: float = 0.20) -> Image.Image:
    mask = ground(WHITE, radius_pct).split()[3]
    colour = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    cd = ImageDraw.Draw(colour)
    cd.rectangle([0, 0, SIDE // 2, SIDE], fill=left)
    cd.rectangle([SIDE // 2, 0, SIDE, SIDE], fill=right)
    out = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    out.paste(colour, (0, 0), mask)
    return out


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def count_ink(img: Image.Image, threshold: int = 20) -> int:
    """Count pixels with alpha above `threshold`, via numpy (the pure-python
    per-pixel loop this replaced took too long at SIDE=2048 to call from
    inside a search loop)."""
    a = np.asarray(img.split()[3])
    return int((a > threshold).sum())


def clipped_px(scene: Image.Image, field: Image.Image) -> int:
    """How many of the scene's own ink pixels get thrown away when it is
    masked by the field's rounded-corner alpha (round 5 v1's D_wordmark bug:
    the wordmark's own ink extended past where the rounded corner still has
    full alpha, and on_field's Image.composite silently dropped it)."""
    before = count_ink(scene)
    empty = Image.new("RGBA", scene.size, (0, 0, 0, 0))
    masked = Image.composite(scene, empty, field.split()[3])
    after = count_ink(masked)
    return before - after


# ==================================================== vanilla texture io ===


def load_crop(
    rel_path: str, box: tuple[int, int, int, int] | None = None
) -> Image.Image:
    img = Image.open(VANILLA / rel_path).convert("RGBA")
    return img.crop(box) if box else img.crop(img.getbbox())


def place_nearest(
    dest: Image.Image, sprite: Image.Image, k: int, cx: int, cy: int
) -> tuple[int, int, int, int]:
    scaled = sprite.resize((sprite.width * k, sprite.height * k), Image.NEAREST)
    x0, y0 = cx - scaled.width // 2, cy - scaled.height // 2
    dest.alpha_composite(scaled, (x0, y0))
    return (x0, y0, scaled.width, scaled.height)


# =================================================== group 1: candidate A ===
# appleskin's device (cut into something ordinary, show the hidden interior)
# applied to a slice of ground rather than a bitten apple. The strata swatch
# is real dirt.png / stone.png rows, not a redrawn gradient. The notch is a
# rectangular window, blocky (Minecraft has no round edges - a round bite
# would be the same "recalled outline" failure round 3 already paid for),
# and cut *inside* the tile rather than at a corner - a corner notch sat
# right where the field's own rounded-corner mask clips the composite, and
# clipped the torch's flame off (round 5 v1's visible bug).

REDSTONE_TORCH = "block/redstone_torch.png"


def build_strata_swatch() -> Image.Image:
    dirt = Image.open(VANILLA / "block/dirt.png").convert("RGBA")
    stone = Image.open(VANILLA / "block/stone.png").convert("RGBA")
    swatch = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    swatch.paste(dirt.crop((0, 0, 16, 6)), (0, 0))
    swatch.paste(stone.crop((0, 6, 16, 16)), (0, 6))
    px = swatch.load()
    # rectangular window, inset from every edge - a peephole into the strata.
    # Coordinator round: the torch read at full size but was too small a
    # fraction of the frame to survive the 48px downsample. Enlarged from
    # 5x7 to 6x8 (out of the 16x16 swatch) to give the torch more room.
    notch = [
        (9, 4, 15, 12),
    ]
    for x0, y0, x1, y1 in notch:
        for yy in range(y0, y1):
            for xx in range(x0, x1):
                px[xx, yy] = (0, 0, 0, 0)
    return swatch


def cand_cutaway() -> tuple[Image.Image, Image.Image]:
    scene, _ = canvas()
    swatch = build_strata_swatch()
    box = place_nearest(scene, swatch, 108, SIDE // 2, SIDE // 2)  # bbox target ~85%
    x0, y0, w, h = box
    torch = load_crop(REDSTONE_TORCH, (6, 5, 10, 12))  # flame + top of the stick, 4x7
    # centre of the enlarged window is (12, 8) in the swatch's own 16x16
    # space. Overlay scale raised 54 -> 80 (round 5 v1 -> this round) so the
    # torch itself, not just the window it sits in, is bigger at 48px.
    fx = x0 + int(12.0 / 16 * w)
    fy = y0 + int(8.0 / 16 * h)
    place_nearest(scene, torch, 80, fx, fy)
    return on_field(scene, ground(FIELD_A)), scene


# =================================================== group 1: candidate B ===
# xaeros's device (the icon IS the product's own subject) applied as a
# small chunk grid: most cells idle, one distant cell still carrying a lit
# spark - the exact thing this mod's tests watch a chunk do while unloaded.

# Coordinator round: 5x5 cells (20 units each - 15.6% of the frame) with
# only the fine redstone_dust_dot sprite marking the accent cell were both
# below the noise floor at 48px - the grid survived the downsample, the one
# lit cell did not. Dropped to 4x4 (26-unit cells, still reads as "a grid
# of chunks", the coordinator's floor) and the accent is now a bold flat
# diamond first, with the dust sprite layered on for texture at 256/512 -
# the diamond alone is what has to survive 48px, not the sprite's detail.
GRID_N = 4
GRID_CELL = 26  # design units per cell
GRID_SPAN = GRID_N * GRID_CELL  # 104 units of 128
GRID_ORIGIN = (GRID - GRID_SPAN) / 2

CELL_TONES = [mix(STONE, BLACK, 0.35), mix(DIRT, BLACK, 0.35)]


def cand_chunkgrid() -> tuple[Image.Image, Image.Image]:
    scene, d = canvas()
    accent_cell = (3, 1)  # off-centre - "the one you can't see"
    for gy in range(GRID_N):
        for gx in range(GRID_N):
            x0 = GRID_ORIGIN + gx * GRID_CELL
            y0 = GRID_ORIGIN + gy * GRID_CELL
            if (gx, gy) == accent_cell:
                tone = with_hsv(REDSTONE, s=0.72, v=0.58)
            else:
                tone = CELL_TONES[(gx + gy * 2) % 2]
            rect(
                d, x0 + 0.8, y0 + 0.8, x0 + GRID_CELL - 0.8, y0 + GRID_CELL - 0.8, tone
            )
    ax0 = GRID_ORIGIN + accent_cell[0] * GRID_CELL
    ay0 = GRID_ORIGIN + accent_cell[1] * GRID_CELL
    acx, acy = ax0 + GRID_CELL / 2, ay0 + GRID_CELL / 2
    r = GRID_CELL * 0.32
    poly(
        d,
        [(acx, acy - r), (acx + r, acy), (acx, acy + r), (acx - r, acy)],
        CREAM,
    )
    dot = load_crop("block/redstone_dust_dot.png")
    place_nearest(
        scene,
        dot,
        int(GRID_CELL * U / dot.width * 0.62),
        int(acx * U),
        int(acy * U),
    )
    return on_field(scene, ground(FIELD_B)), scene


# =================================================== group 1: candidate C ===
# unloaded-activity's device (two-tone split panel) reused for what
# "meanwhile" names - elsewhere, at the same time - not round 4's
# before/after: the identical spark sits at the identical position on both
# halves, dim on the left (away) and lit on the right (here). No arrow.


def cand_split() -> tuple[Image.Image, Image.Image]:
    scene, _ = canvas()
    dot = load_crop("block/redstone_dust_dot.png")
    # each spark fills ~75% of its own half - one bold subject per half,
    # not a small accent (round 5 v1 rendered these too small to read at 48px)
    k = 112
    place_nearest(scene, dot, k, SIDE // 4, int(SIDE * 0.52))
    place_nearest(scene, dot, k, SIDE * 3 // 4, int(SIDE * 0.52))
    field = split_field(FIELD_C_DIM, FIELD_C_LIT)
    return on_field(scene, field), scene


# =================================================== group 2: candidate D ===
# round 2's D_letters direction, rebuilt at anchor strength: tighter
# kerning, heavier stroke, bbox pushed to the anchor band, saturated field.

GLYPHS = {
    "M": ["10001", "11011", "10101", "10001", "10001", "10001", "10001"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "E": ["1111", "1000", "1000", "1110", "1000", "1000", "1111"],
    "A": ["0110", "1001", "1001", "1111", "1001", "1001", "1001"],
    "N": ["1001", "1101", "1101", "1011", "1011", "1001", "1001"],
    "H": ["1001", "1001", "1001", "1111", "1001", "1001", "1001"],
    "I": ["1111", "0110", "0110", "0110", "0110", "0110", "1111"],
    "L": ["1000", "1000", "1000", "1000", "1000", "1000", "1111"],
}


def text_width(s: str, scale: int, tracking: int = 1) -> int:
    return sum(len(GLYPHS[c][0]) * scale for c in s) + tracking * scale * (len(s) - 1)


def draw_text(
    d, s: str, x: int, y: int, scale: int, col: Color, tracking: int = 1
) -> None:
    cx = x
    for ch in s:
        g = GLYPHS[ch]
        for ry, row in enumerate(g):
            run = 0
            for rx, bit in enumerate(row + "0"):
                if bit == "1":
                    run += 1
                    continue
                if run:
                    rect(
                        d,
                        cx + (rx - run) * scale,
                        y + ry * scale,
                        cx + rx * scale,
                        y + (ry + 1) * scale,
                        col,
                    )
                    run = 0
        cx += (len(g[0]) + tracking) * scale


def cand_wordmark() -> tuple[Image.Image, Image.Image]:
    # round 5 v1 used scale 5, tracking 1: WHILE (the longer line) came out
    # 125 of 128 grid units wide - a 1.5-unit margin each side, 6px of the
    # 512px tile. Measured directly with clipped_px (pixel-exact, not the
    # geometry estimate below): at that margin, zero ink pixels actually
    # fall outside the field's rounded-corner alpha, correcting an
    # overclaim an earlier draft of this comment made ("on_field's
    # compositing silently dropped part of the glyph") - it did not; the
    # E's own strokes stop a couple of source pixels short of where the
    # corner starts curving away. 6px of margin is just tight enough to
    # *look* cut in a downsized contact-sheet tile without a pixel lost.
    #
    # Fixed anyway, because "technically zero pixels lost" was not the bar
    # the coordinator was reading against. The corner radius (20% of the
    # side = 25.6 units) needs roughly R*(1-1/sqrt(2)) ~= 7.5 units of
    # margin for a rectangle's own corner to clear the curve at all; the
    # search below requires close to double that (8 units) so the margin
    # reads as deliberate rather than merely technically-nonzero, and it
    # still verifies with clipped_px on every attempt rather than trusting
    # the arithmetic alone.
    field = ground(FIELD_D)
    top, bot = "MEAN", "WHILE"
    MIN_MARGIN = 8.0
    for scale in (5.0, 4.8, 4.6, 4.4, 4.2, 4.0, 3.8):
        tracking = 1
        gap = round(scale)
        scene, d = canvas()
        wt = text_width(top, scale, tracking)
        wb = text_width(bot, scale, tracking)
        line_h = 7 * scale
        total_h = line_h * 2 + gap
        cx, cy = GRID / 2, GRID / 2
        y0 = cy - total_h / 2
        draw_text(
            d, top, round(cx - wt / 2), round(y0), scale, tracking=tracking, col=CREAM
        )
        draw_text(
            d,
            bot,
            round(cx - wb / 2),
            round(y0 + line_h + gap),
            scale,
            tracking=tracking,
            col=CREAM,
        )
        clipped = clipped_px(scene, field)
        bbox_w = max(wt, wb) / GRID
        margin_units = (GRID - max(wt, wb)) / 2
        print(
            f"  D_wordmark try scale={scale}: bbox_w={bbox_w:.1%} "
            f"margin={margin_units:.1f}u  clipped_px={clipped}"
        )
        if clipped == 0 and margin_units >= MIN_MARGIN:
            return on_field(scene, field), scene
    raise RuntimeError(
        "cand_wordmark: no scale in the tried range cleared the corner mask "
        f"with >= {MIN_MARGIN}u margin"
    )


# =================================================== group 2: candidate E ===
# a typographic mark rather than an object silhouette: a bold pixel
# parenthesis pair, "(...)" - the punctuation for an aside happening at the
# same time as the main clause, which is what "meanwhile" is grammatically
# for. Nothing rounds 1-4 tried.

BRACKET_GLYPHS = {
    "(": [
        "0011",
        "0100",
        "1000",
        "1000",
        "1000",
        "1000",
        "1000",
        "1000",
        "1000",
        "1000",
        "0100",
        "0011",
    ],
    ")": [
        "1100",
        "0010",
        "0001",
        "0001",
        "0001",
        "0001",
        "0001",
        "0001",
        "0001",
        "0001",
        "0010",
        "1100",
    ],
}


def draw_glyphs(
    d, s: str, glyphs: dict, x: int, y: int, scale: int, col: Color, tracking: int = 1
) -> None:
    cx = x
    for ch in s:
        g = glyphs[ch]
        for ry, row in enumerate(g):
            run = 0
            for rx, bit in enumerate(row + "0"):
                if bit == "1":
                    run += 1
                    continue
                if run:
                    rect(
                        d,
                        cx + (rx - run) * scale,
                        y + ry * scale,
                        cx + rx * scale,
                        y + (ry + 1) * scale,
                        col,
                    )
                    run = 0
        cx += (len(g[0]) + tracking) * scale


def glyphs_width(s: str, glyphs: dict, scale: int, tracking: int = 1) -> int:
    return sum(len(glyphs[c][0]) * scale for c in s) + tracking * scale * (len(s) - 1)


def cand_bracket() -> tuple[Image.Image, Image.Image]:
    scene, d = canvas()
    # tracking 2 (round 5 v1) let the two glyphs' curved caps meet, so the
    # pair read as one closed ring - indistinguishable from a badge/gear
    # outline at 48px (negative_list's ring/badge tell, and too close to
    # yacl's own ring-with-hole mark). tracking 7 opens a clear gap so the
    # two brackets read as a pair with something between them.
    scale, tracking = 8, 7
    w = glyphs_width("()", BRACKET_GLYPHS, scale, tracking)
    h = 12 * scale
    x0 = round(GRID / 2 - w / 2)
    y0 = round(GRID / 2 - h / 2)
    draw_glyphs(d, "()", BRACKET_GLYPHS, x0, y0, scale, CREAM, tracking)
    return on_field(scene, ground(FIELD_E)), scene


# =================================================== group 2: candidate F ===
# round 5 v1 drew a flat two-tone flame/ember. Opened
# branding/gate0/icons/sodium-extra_orig.webp on the coordinator's word and
# it is exactly that silhouette - a teardrop flame, single glyph, flat
# field - recoloured. Sodium (green+white) is a second flame on the same
# shelf. Two of the seven group 2 anchors already own "flame", so a third
# one reads as "another performance-mod flame", which is the one thing
# this mod's branding cannot say (it is not a performance mod).
#
# Replaced with a mark no reference in the 33-icon set uses: two diamonds,
# offset diagonally, the back one dim and the front one bright - an echo,
# not an object. Nothing to mistake for a fire/water/lightning glyph
# (the exhausted set among the 33: sodium, sodium-extra, redstone-chunk-
# loader's water drops, immediatelyfast's flame-arrow) because it isn't
# drawn from any physical thing - same footing as candidate E's brackets,
# a typographic/graphic device rather than a silhouette.

ECHO_DIAMOND_R = 42  # design units, half-diagonal
ECHO_OFFSET = 18  # design units between the ghost and the real diamond


def _diamond(cx: float, cy: float, r: float) -> list[tuple[float, float]]:
    return [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]


def cand_echo() -> tuple[Image.Image, Image.Image]:
    scene, d = canvas()
    cx, cy = GRID / 2, GRID / 2
    half = ECHO_OFFSET / 2
    poly(d, _diamond(cx - half, cy - half, ECHO_DIAMOND_R), ECHO_BACK)
    poly(d, _diamond(cx + half, cy + half, ECHO_DIAMOND_R), ECHO_FRONT)
    return on_field(scene, ground(FIELD_F)), scene


# ================================================================ sheets ===

CLAIMS = {
    "A_cutaway": "group1 - appleskin's device: cut it open, show what's still running inside",
    "B_chunkgrid": "group1 - xaeros's device: the icon is the thing the mod watches (a live, unseen chunk)",
    "C_split": "group1 - unloaded-activity's device: elsewhere, at the same time - no arrow, no transform",
    "D_wordmark": "group2 - the name, at anchor strength (round2's D_letters, rebuilt)",
    "E_bracket": "group2 - a typographic mark: the aside that runs alongside the main clause",
    "F_echo": "group2 - two diamonds, offset and dimmed - the same thing, a beat behind, catching up",
}

GROUPS = {
    "A_cutaway": 1,
    "B_chunkgrid": 1,
    "C_split": 1,
    "D_wordmark": 2,
    "E_bracket": 2,
    "F_echo": 2,
}

CANDIDATES = {
    "A_cutaway": cand_cutaway,
    "B_chunkgrid": cand_chunkgrid,
    "C_split": cand_split,
    "D_wordmark": cand_wordmark,
    "E_bracket": cand_bracket,
    "F_echo": cand_echo,
}


def label_font(size: int):
    for path in (
        "C:/Windows/Fonts/segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def metrics(scene: Image.Image) -> tuple[float, float]:
    alpha = scene.convert("RGBA").split()[3]
    w, h = alpha.size
    a = alpha.load()
    xs, ys, n = [], [], 0
    for yy in range(0, h, 2):
        for xx in range(0, w, 2):
            if a[xx, yy] > 20:
                xs.append(xx)
                ys.append(yy)
                n += 1
    if not xs:
        return (0.0, 0.0)
    bbox = max(max(xs) - min(xs), max(ys) - min(ys)) / w
    return (bbox, n / ((w / 2) * (h / 2)))


def contact_sheet(
    icons: dict[str, Image.Image], metrics_map: dict[str, tuple[float, float]]
) -> Image.Image:
    TILE, PAD, LABEL = 340, 22, 70
    cols = 3
    rows_n = (len(icons) + cols - 1) // cols
    sheet = Image.new(
        "RGB",
        (PAD + cols * (TILE + PAD), PAD + rows_n * (TILE + LABEL + PAD)),
        (52, 54, 60),
    )
    sd = ImageDraw.Draw(sheet)
    f_title, f_claim = label_font(15), label_font(13)
    checker_bg = Image.new("RGB", (TILE, TILE), (238, 238, 238))
    for i, (name, icon) in enumerate(icons.items()):
        cx = PAD + (i % cols) * (TILE + PAD)
        cy = PAD + (i // cols) * (TILE + LABEL + PAD) + LABEL
        tile = checker_bg.copy()
        big = icon.resize((TILE, TILE), Image.LANCZOS)
        tile.paste(big, (0, 0), big)
        sheet.paste(tile, (cx, cy))
        x = cx + TILE - 6
        for size in (96, 64, 48):
            x -= size
            ins_bg = Image.new("RGB", (size, size), (238, 238, 238))
            ins = icon.resize((size, size), Image.LANCZOS)
            ins_bg.paste(ins, (0, 0), ins)
            sheet.paste(ins_bg, (x, cy + TILE - size - 6))
            sd.rectangle(
                [x, cy + TILE - size - 6, x + size, cy + TILE - 6],
                outline=(140, 140, 140),
            )
            x -= 8
        bbox, cov = metrics_map[name]
        grp = GROUPS[name]
        sd.text(
            (cx + 2, cy - LABEL + 2),
            f"[group{grp}] {name}   bbox {bbox:.0%}  coverage {cov:.0%}",
            fill=(238, 238, 238),
            font=f_title,
        )
        claim = CLAIMS[name]
        line, lines = "", []
        for word in claim.split():
            trial = f"{line} {word}".strip()
            if sd.textlength(trial, font=f_claim) > TILE - 6:
                lines.append(line)
                line = word
            else:
                line = trial
        lines.append(line)
        for k, ln in enumerate(lines[:3]):
            sd.text(
                (cx + 2, cy - LABEL + 22 + k * 15),
                ln,
                fill=(178, 180, 186),
                font=f_claim,
            )
    return sheet


def size_check(icons: dict[str, Image.Image]) -> Image.Image:
    GUT, ROW = 26, 64 + 48 + 34
    w = GUT + len(icons) * (64 + GUT)
    img = Image.new("RGB", (w, 2 * (ROW + GUT) + GUT), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle(
        [0, ROW + GUT, w, img.height], fill=(30, 31, 34)
    )  # top band = CurseForge white, bottom = Modrinth dark (#1e1f22)
    f = label_font(11)
    for band, y0, ink in ((0, GUT, (40, 40, 40)), (1, ROW + 2 * GUT, (225, 225, 228))):
        for i, (name, icon) in enumerate(icons.items()):
            x = GUT + i * (64 + GUT)
            bg = (255, 255, 255) if band == 0 else (30, 31, 34)
            t64 = Image.new("RGB", (64, 64), bg)
            i64 = icon.resize((64, 64), Image.LANCZOS)
            t64.paste(i64, (0, 0), i64)
            img.paste(t64, (x, y0))
            t48 = Image.new("RGB", (48, 48), bg)
            i48 = icon.resize((48, 48), Image.LANCZOS)
            t48.paste(i48, (0, 0), i48)
            img.paste(t48, (x + 8, y0 + 64 + 10))
            d.text((x, y0 + 64 + 48 + 16), name[:1], fill=ink, font=f)
        del band
    return img


def main() -> None:
    CAND.mkdir(parents=True, exist_ok=True)
    icons: dict[str, Image.Image] = {}
    metrics_map: dict[str, tuple[float, float]] = {}
    # every candidate's field is a ground()/split_field() rounded rect at the
    # default 20% radius, so one generic mask is enough to check all six for
    # the exact bug D_wordmark had (ink extending past where the rounded
    # corner still has full alpha) - this is the check that should have run
    # before the first sheet went out, not just for the one caught by eye.
    generic_mask = ground(WHITE).split()[3]
    empty = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    for name, fn in CANDIDATES.items():
        raw_icon, raw_scene = fn()
        clipped = count_ink(raw_scene) - count_ink(
            Image.composite(raw_scene, empty, generic_mask)
        )
        status = "OK" if clipped == 0 else f"CLIPPED {clipped}px"
        icon = finish(raw_icon)
        for size in (512, 256, 128, 64, 48):
            out = icon if size == 512 else icon.resize((size, size), Image.LANCZOS)
            out.save(CAND / f"meanwhile_{name}_{size}.png")
        icons[name] = icon
        bbox, cov = metrics(raw_scene)
        metrics_map[name] = (bbox, cov)
        print(
            f"{name:14s} group{GROUPS[name]}  bbox {bbox:5.1%}  coverage {cov:5.1%}  corner-clip {status}"
        )
    contact_sheet(icons, metrics_map).save(CAND / "_contact_sheet_r5b.png")
    size_check(icons).save(CAND / "_small_size_check_r5b.png")
    print("\nsaved", CAND / "_contact_sheet_r5b.png")
    print("saved", CAND / "_small_size_check_r5b.png")


if __name__ == "__main__":
    main()
