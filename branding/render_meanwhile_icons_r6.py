# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 6 candidates.

Round 5's coordinator verdict (2026-08-16, this session): A_cutaway,
B_chunkgrid and F_echo are "complete garbage" - not a concept problem, a
craft problem. Looking at them next to the five brand-mark anchors
(sodium/iris/lithium/architectury-api/immediatelyfast) side by side, the
failure is consistent across all three: they are diagrams (a cutaway
window, a UI-mockup grid, an arbitrary geometric pair), not marks. Their
colours are muddy (dirt-brown, slate-grey, near-black-red) where the
anchors are confidently saturated. And each has more than one thing
competing for attention where the anchors have exactly one.

D_wordmark was not named in the "garbage" verdict - it survives as a live
direction. C_split and E_bracket were already cut earlier for reasons
unrelated to craft (icon collision, not quality).

This round does two things, both graded against the anchor numbers
measured in round 5 (bbox 75-100%, coverage 19-53%, connected components
mostly 1, occasionally 3 - see render_meanwhile_icons_r5.py's docstring
for the per-anchor table and measurement method, reused verbatim below):

  1. Three wordmark variants (group 2a) that develop D's direction along
     three different axes rather than one incremental tweak:
       W1_stacked   D's own two-line "MEAN / WHILE" lockup, ported as-is
                    (copper field, cream ink) - proven to clear the field's
                    rounded corner at zero clipped px, not touched further.
       W2_monogram  drops to a single bold letterform ("M"), built as one
                    filled polygon (not the compact bitmap font, which
                    fragments into 3 disconnected strokes at this weight -
                    see the comment on GLYPHS below). A monogram is the
                    structural fix for "48px is a wordmark's structural
                    weakness": there is no multi-letter kerning to lose
                    when there is only one letter.
       W3_band      "WHILE" alone, one line, at anchor stroke-weight scale.
                    Drops "MEAN" rather than shrinking both words to fit -
                    LOGO_PLAYBOOK's suggested axis ("语の一部だけを強調する").
                    Chosen over a full "MEANWHILE" one-liner because 9
                    letters at anchor stroke weight cannot fit inside an
                    85%+ bbox without the strokes going sub-pixel at 48px;
                    "WHILE" alone (5 glyphs) can.
     Each variant's 48px residue is designed before drawing (LOGO_PLAYBOOK's
     instruction, not skipped this round): W1 -> a two-bar warm block even
     if letterforms blur; W2 -> a single bold glyph, legible by
     construction since it is one shape at near-anchor scale; W3 -> a
     bright horizontal text band, readable or not depending on final-pass
     downsample but never disappearing into noise the way a 9-letter line
     would.

  2. Three new marks (group 3, not reusing A/B/C/E/F's motifs or any
     motif on the banned list below) built the same way the five brand-mark
     anchors are built: one real object, one flat saturated field, nothing
     else competing for attention. All three subjects are real vanilla
     item/block textures (LOGO_PLAYBOOK's standing rule - Minecraft things
     are drawn from real pixels, not redrawn from memory), each already a
     single 4-connected shape in its native asset (verified below, not
     assumed) so no manual outline-tracing was needed:
       G_spyglass   item/spyglass.png. Looking at something happening
                    somewhere else is the literal action "meanwhile" the
                    word describes - an aside, elsewhere, at the same time.
                    Diagonal tube, single shape, copper+teal ink, unused by
                    any of the 33 reference icons.
       G_bundle     item/bundle.png. The one existing motif shared by both
                    this MOD's actual mechanism and the object: a bundle
                    carries any item without needing to know what it is,
                    the same way this mod's generic-NBT-diff advances any
                    block entity without a type registry. The closest this
                    round comes to "function-guessable" (AppleSkin's
                    register) rather than "brand-strong" alone.
       G_watcher    block/observer_front.png, cropped to just the lighter
                    face-plate (excludes the dark top strip and the
                    rough-stone border, which read as texture noise, not
                    signal, at 48px). A face with two eyes, watching - "the
                    machine that's still keeping track when you're not
                    looking" - is closer to this mod's actual claim than
                    "elsewhere" alone, and observer is the one vanilla
                    block whose entire in-game job is "notices a change
                    nobody was watching for".

Colour: six different hue families across the six candidates (copper,
redstone red, gold, diamond cyan, emerald green, amethyst violet), each
sampled from a real vanilla block average and retargeted into the anchor
S/V band with with_hsv - same technique round 5 used, not a new one. No
two candidates share a field hue this round, which round 5 did not
attempt (A/C/F all sat in the same dirt/redstone-adjacent dark register -
plausibly part of why they read as "muddy" next to each other on the
sheet, not just individually).

Banned this round (named failures + kura's explicit exclusion list):
  furnace front, hopper, plain right-pointing arrow, raw->cooked
  before/after, clock face, strata swatch (A), chunk grid (B), offset
  diamond pair (F), flame/embers, parentheses/ring/annulus (E),
  in-world diorama background. Also not reused: C's split-panel device,
  and round 5's own two-line-wordmark-only idea is *not* the sole wordmark
  candidate this round (W2/W3 are structurally different, not palette
  swaps of W1).

SS=4 (2048px composite -> 512 LANCZOS). Pixel-art subjects placed at an
integer NEAREST factor before the final reduction, per standing rule.
"""

from __future__ import annotations

import colorsys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

# ================================================================ setup ===

SIDE = 2048  # SS=4 composite size
OUT = 512
GRID = 128  # design-unit grid the composite is authored on
U = SIDE // GRID

BRANDING = Path(__file__).resolve().parent
CAND = BRANDING / "icon-candidates-r6"
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

COPPER = sample_avg("block/copper_block.png")
REDSTONE = sample_avg("block/redstone_block.png")
GOLD = sample_avg("block/gold_block.png")
DIAMOND = sample_avg("block/diamond_block.png")
EMERALD = sample_avg("block/emerald_block.png")
AMETHYST = sample_avg("block/amethyst_block.png")

FIELD_W1 = with_hsv(COPPER, s=0.62, v=0.86)  # W1_stacked - unchanged from r5's D
FIELD_W2 = with_hsv(REDSTONE, s=0.70, v=0.90)  # W2_monogram
FIELD_W3 = with_hsv(GOLD, s=0.66, v=0.92)  # W3_band
FIELD_SPYGLASS = with_hsv(DIAMOND, s=0.42, v=0.86)  # cyan, kept paler - diamond
# block averages near-white and pushing saturation too hard turns it teal-slate
FIELD_BUNDLE = with_hsv(EMERALD, s=0.62, v=0.72)
FIELD_WATCHER = with_hsv(AMETHYST, s=0.58, v=0.66)

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


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def count_ink(img: Image.Image, threshold: int = 20) -> int:
    a = np.asarray(img.split()[3])
    return int((a > threshold).sum())


def clipped_px(scene: Image.Image, field: Image.Image) -> int:
    """How many of the scene's own ink pixels get thrown away by the
    field's rounded-corner alpha mask (round 5's D_wordmark bug class)."""
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


def source_components(
    rel_path: str, box: tuple[int, int, int, int] | None = None
) -> int:
    """4-connected component count of a source sprite's own alpha, before
    it is placed on any field - i.e. is this asset already a single shape,
    or would placing it on a field silently paste in several disconnected
    islands. Checked once per new subject this round rather than assumed."""
    im = load_crop(rel_path, box)
    a = np.asarray(im.split()[3]) > 20
    _, n = ndimage.label(a, structure=[[0, 1, 0], [1, 1, 1], [0, 1, 0]])
    return n


# =================================================== group 2a: wordmarks ===
# GLYPHS below is the compact bitmap font r5's D_wordmark used for running
# text at small scale. Tried directly as a standalone monogram (single "M"
# at large scale) it fragments: row 0 has ink only at columns 0 and 4 (the
# serif-style open-top V), so at 4-connectivity the left stroke, right
# stroke, and the two-pixel valley accent in rows 1-2 are three separate
# islands with no shared border - fine for running text where neighbouring
# letters fill the gaps, wrong for a single glyph meant to read as one
# mark. W2_monogram below is a separate hand-built polygon for exactly
# this reason - verified with source_components-style counting on the
# rendered scene, not assumed from the coordinates.

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


def text_width(s: str, scale: float, tracking: int = 1) -> int:
    return sum(len(GLYPHS[c][0]) * scale for c in s) + tracking * scale * (len(s) - 1)


def draw_text(
    d, s: str, x: float, y: float, scale: float, col: Color, tracking: int = 1
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


def cand_w1_stacked() -> tuple[Image.Image, Image.Image]:
    """Ported unchanged from r5's cand_wordmark: two-line MEAN/WHILE,
    scale search for zero-clipped-px against the field's rounded corner."""
    field = ground(FIELD_W1)
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
        margin_units = (GRID - max(wt, wb)) / 2
        if clipped == 0 and margin_units >= MIN_MARGIN:
            return on_field(scene, field), scene
    raise RuntimeError("cand_w1_stacked: no scale cleared the corner mask")


def cand_w3_band() -> tuple[Image.Image, Image.Image]:
    """ "WHILE" alone, one line. Drops MEAN entirely rather than shrinking
    both words - LOGO_PLAYBOOK's "emphasize part of the word" axis."""
    field = ground(FIELD_W3)
    word = "WHILE"
    MIN_MARGIN = 7.0
    for scale in (5.2, 5.0, 4.8, 4.6, 4.4, 4.2, 4.0):
        tracking = 1
        scene, d = canvas()
        w = text_width(word, scale, tracking)
        h = 7 * scale
        cx, cy = GRID / 2, GRID / 2
        draw_text(
            d,
            word,
            round(cx - w / 2),
            round(cy - h / 2),
            scale,
            tracking=tracking,
            col=CREAM,
        )
        clipped = clipped_px(scene, field)
        margin_units = (GRID - w) / 2
        if clipped == 0 and margin_units >= MIN_MARGIN:
            return on_field(scene, field), scene
    raise RuntimeError("cand_w3_band: no scale cleared the corner mask")


# a single bold "M", built as one filled polygon (see the GLYPHS comment
# above for why the bitmap font is not used here). Defined in a local
# 0-100 unit box: two outer verticals, a V notch cut from the top down to
# y=88 (not all the way to the base), leaving a 12-unit solid strip at the
# bottom - guarantees the fill is one 4-connected region regardless of the
# notch depth, verified below rather than assumed.
M_POLY = [
    (0, 100),
    (0, 0),
    (26, 0),
    (50, 88),
    (74, 0),
    (100, 0),
    (100, 100),
    (74, 100),
    (74, 34),
    (50, 100),
    (26, 34),
    (26, 100),
]
# (the inner edge dips back up to (74,34)/(26,34) and the valley touches
# the very base at (50,100) - this carves the V open all the way through,
# giving two legs with a true gap between them rather than the closed-base
# "mountain" shape a simpler 8-point outline would leave. Still one
# polygon, so still one fill region: the two legs are joined along the top
# slab from (26,0)-(74,0) before the notch begins.)


def cand_w2_monogram() -> tuple[Image.Image, Image.Image]:
    field = ground(FIELD_W2)
    for size in (98, 94, 90, 86, 82):
        scene, d = canvas()
        cx, cy = GRID / 2, GRID / 2
        x0, y0 = cx - size / 2, cy - size / 2
        pts = [(x0 + px / 100 * size, y0 + py / 100 * size) for px, py in M_POLY]
        poly(d, pts, CREAM)
        clipped = clipped_px(scene, field)
        if clipped == 0:
            return on_field(scene, field), scene
    raise RuntimeError("cand_w2_monogram: no size cleared the corner mask")


# ======================================================== group 3: marks ===


def cand_spyglass() -> tuple[Image.Image, Image.Image]:
    field = ground(FIELD_SPYGLASS)
    sprite = load_crop("item/spyglass.png")  # 14x14, real item pixels
    for k in (120, 112, 104, 96, 88):
        scene, _ = canvas()
        place_nearest(scene, sprite, k, SIDE // 2, SIDE // 2)
        clipped = clipped_px(scene, field)
        if clipped == 0:
            return on_field(scene, field), scene
    raise RuntimeError("cand_spyglass: no scale cleared the corner mask")


def cand_bundle() -> tuple[Image.Image, Image.Image]:
    field = ground(FIELD_BUNDLE)
    sprite = load_crop("item/bundle.png")  # 16x14, real item pixels
    for k in (116, 108, 100, 92, 84):
        scene, _ = canvas()
        place_nearest(scene, sprite, k, SIDE // 2, SIDE // 2)
        clipped = clipped_px(scene, field)
        if clipped == 0:
            return on_field(scene, field), scene
    raise RuntimeError("cand_bundle: no scale cleared the corner mask")


# observer_front.png is 16x16: rows 0-3 are the dark mounting strip/frame,
# rows 4-5 a rough-stone transition, rows 6-13 the lighter face plate
# carrying the two black "eyes" (cols 3-4 and 11-12 of row 9) and mouth.
# Cropped to (1, 6, 15, 14) - the face plate only, border/frame dropped -
# because the frame reads as texture noise at 48px, not as part of the
# mark (checked directly: box below verified against the raw pixel dump,
# not eyeballed from the enlarged preview alone).
OBSERVER_FACE_BOX = (1, 6, 15, 14)


def cand_watcher() -> tuple[Image.Image, Image.Image]:
    field = ground(FIELD_WATCHER)
    sprite = load_crop("block/observer_front.png", OBSERVER_FACE_BOX)  # 14x8
    for k in (108, 100, 92, 84, 76):
        scene, _ = canvas()
        place_nearest(scene, sprite, k, SIDE // 2, SIDE // 2)
        clipped = clipped_px(scene, field)
        if clipped == 0:
            return on_field(scene, field), scene
    raise RuntimeError("cand_watcher: no scale cleared the corner mask")


# ================================================================ sheets ===

CLAIMS = {
    "W1_stacked": "group2a - MEAN/WHILE two-line, unchanged from r5's D (still alive)",
    "W2_monogram": "group2a - single bold M, one polygon: 48px-proof by construction",
    "W3_band": "group2a - WHILE alone, one line, anchor stroke weight",
    "G_spyglass": "group3 - looking at what's happening elsewhere, at the same time",
    "G_bundle": "group3 - carries anything without needing to know what it is",
    "G_watcher": "group3 - a face that keeps track when nobody's looking",
}

GROUPS = {
    "W1_stacked": "2a",
    "W2_monogram": "2a",
    "W3_band": "2a",
    "G_spyglass": "3",
    "G_bundle": "3",
    "G_watcher": "3",
}

CANDIDATES = {
    "W1_stacked": cand_w1_stacked,
    "W2_monogram": cand_w2_monogram,
    "W3_band": cand_w3_band,
    "G_spyglass": cand_spyglass,
    "G_bundle": cand_bundle,
    "G_watcher": cand_watcher,
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


def metrics(scene: Image.Image) -> tuple[float, float, int]:
    """bbox occupancy + coverage (sampled every 2px, matching r5) plus an
    exact 4-connected component count over the full-resolution alpha mask
    (r5's own metrics() did not compute this; added here since the report
    for this round needs it per candidate, not just bbox/coverage)."""
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
        return (0.0, 0.0, 0)
    bbox = max(max(xs) - min(xs), max(ys) - min(ys)) / w
    cov = n / ((w / 2) * (h / 2))
    full = np.asarray(alpha) > 20
    _, ncomp = ndimage.label(full, structure=[[0, 1, 0], [1, 1, 1], [0, 1, 0]])
    return (bbox, cov, int(ncomp))


def contact_sheet(
    icons: dict[str, Image.Image], metrics_map: dict[str, tuple[float, float, int]]
) -> Image.Image:
    TILE, PAD, LABEL = 340, 22, 78
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
        bbox, cov, ncomp = metrics_map[name]
        grp = GROUPS[name]
        sd.text(
            (cx + 2, cy - LABEL + 2),
            f"[group{grp}] {name}   bbox {bbox:.0%}  cov {cov:.0%}  comp {ncomp}",
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
            d.text((x, y0 + 64 + 48 + 16), name[:2], fill=ink, font=f)
        del band
    return img


def main() -> None:
    CAND.mkdir(parents=True, exist_ok=True)

    # sanity check: are the three new real-texture subjects single
    # 4-connected shapes in their own native asset, before they ever touch
    # a field? (per the module docstring - checked, not assumed)
    for label, rel, box in (
        ("spyglass", "item/spyglass.png", None),
        ("bundle", "item/bundle.png", None),
        ("observer face crop", "block/observer_front.png", OBSERVER_FACE_BOX),
    ):
        n = source_components(rel, box)
        print(f"source check: {label:20s} -> {n} component(s) in native asset")

    icons: dict[str, Image.Image] = {}
    metrics_map: dict[str, tuple[float, float, int]] = {}
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
        bbox, cov, ncomp = metrics(raw_scene)
        metrics_map[name] = (bbox, cov, ncomp)
        print(
            f"{name:14s} group{GROUPS[name]}  bbox {bbox:5.1%}  coverage {cov:5.1%}  "
            f"components {ncomp}  corner-clip {status}"
        )
    contact_sheet(icons, metrics_map).save(CAND / "_contact_sheet_r6.png")
    size_check(icons).save(CAND / "_small_size_check_r6.png")
    print("\nsaved", CAND / "_contact_sheet_r6.png")
    print("saved", CAND / "_small_size_check_r6.png")


if __name__ == "__main__":
    main()
