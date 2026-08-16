# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 13.

Twelve rounds failed because the coordinator kept asking for a single
subject ("Subject-Max"), and "meanwhile" is a relation, not an object: it
needs a here/there or before/after PAIR in the frame, with the pair
showing a *progressed* difference, read as a snapshot (not as something
caught mid-motion). See branding/IMAGE_AND_LOGO_DIRECTION.md for the full
post-mortem. kura picked two directions to try this round: A (comic-panel
convention: "meanwhile, elsewhere") and D (the word "Meanwhile" itself,
the only family never binned in two culls). This file builds both, plus
A+D fusions.

Design targets, measured from the three named anchors (gate0/icons):
  sodium      bg H108 S49 V78 green;  white glyph bbox 50x57%, margin
              ~21-25% all sides, fill ratio 16.3%, 1 connected component
  lithium     bg H261 S50 V77 purple; white glyph bbox 50x50%, margin
              25% all sides, fill ratio 12.2%, 1 connected component
  architectury bg near-black; orange crane bbox 62.5x67%, margin
              ~17-19%, fill ratio 17.4%, 2 significant components
  (measured with branding/measure_anchor_icons.py, see its output for
  the exact run)

sodium/lithium are single small centred monochrome glyphs on a flat
colour field - that vocabulary fits a lone subject, which is exactly
what this round is not drawing. architectury is the closer analogue:
multi-colour, big (>60% bbox), bold flat shapes, a couple of read as
separate parts - that is the model for how much of the frame the
two-item pairs below are allowed to fill. Colour is drawn from the
concept, not decoration: a cool, desaturated "here / before / idle"
slate and a warm, saturated "there / after / advanced" amber, the way
architectury's black field and Reese's orange both come from the
subject rather than a generic brand palette.

No gradients, no radial glow, no badge circles, no dots - flat shapes
only, per ~/dev/documents/design/negative_list.md.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r13"
OUT_DIR.mkdir(exist_ok=True)

SIDE = 2048
OUT = 512
Color = tuple[int, int, int, int]

# ---------------------------------------------------------------- colour ---
# "here / before / idle" - cool, desaturated slate
HERE_BG: Color = (58, 68, 84, 255)  # #3A4454
HERE_FIG: Color = (224, 229, 236, 255)  # pale cool silhouette on slate

# "there / after / advanced" - warm, saturated ripe amber
THERE_BG: Color = (214, 141, 41, 255)  # #D68D29
THERE_FIG: Color = (58, 34, 12, 255)  # dark umber silhouette on amber
THERE_ACCENT: Color = (150, 40, 34, 255)  # deep berry-red, used sparingly

DARK_FIELD: Color = (22, 24, 28, 255)  # #16181C near-black, not pure black
CREAM_FIELD: Color = (231, 227, 218, 255)  # #E7E3DA warm light neutral

GOLD_TEXT: Color = (240, 169, 60, 255)  # vivid gold, for "WHILE"/advanced word
SLATE_TEXT: Color = (110, 118, 130, 255)  # dim slate, for "MEAN"/before word
DARK_TEXT: Color = (30, 24, 16, 255)  # near-black warm text on cream
FADED_TEXT: Color = (176, 170, 158, 255)  # weathered pale text on cream


def P(fx: float, fy: float) -> tuple[float, float]:
    return (fx * SIDE, fy * SIDE)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def flat_field(color: Color) -> Image.Image:
    return Image.new("RGBA", (SIDE, SIDE), color)


def vsplit_field(left: Color, right: Color, split: float = 0.5) -> Image.Image:
    img = Image.new("RGBA", (SIDE, SIDE), left)
    d = ImageDraw.Draw(img)
    d.rectangle([P(split, 0), P(1, 1)], fill=right)
    return img


def hsplit_field(top: Color, bottom: Color, split: float = 0.5) -> Image.Image:
    img = Image.new("RGBA", (SIDE, SIDE), top)
    d = ImageDraw.Draw(img)
    d.rectangle([P(0, split), P(1, 1)], fill=bottom)
    return img


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def font(name: str, frac: float) -> ImageFont.FreeTypeFont:
    size = int(SIDE * frac)
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", size)


def text_wh(
    d: ImageDraw.ImageDraw, s: str, f: ImageFont.FreeTypeFont
) -> tuple[float, float]:
    l, t, r, b = d.textbbox((0, 0), s, font=f)
    return r - l, b - t


def fit_font(
    d: ImageDraw.ImageDraw,
    s: str,
    name: str,
    max_w: float,
    start_frac: float,
) -> tuple[ImageFont.FreeTypeFont, float, float]:
    """Grow/shrink a font so `s` is exactly as wide as it can be without
    exceeding max_w (in canvas px) - fixes the D-series/caption text
    overflowing its margins that r13's first pass shipped with."""
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


# ------------------------------------------------------------- plant pair ---
# Observed, not invented: vanilla's own wheat growth stages
# (_tools/MineTexture/tool_data/cache/vanilla_textures/block/wheat_stage{0,7}.png)
# were opened and studied before drawing anything (negative_list #33 - don't
# draw a remembered silhouette). stage0 is 2-3 short, sparse, thin green
# blades rising a little off the baseline; stage7 is 6-7 tall blades packed
# almost shoulder to shoulder, jagged uneven tips, two-tone (gold body +
# darker olive-ish blades mixed in). draw_blade_tuft below reproduces that
# structure with flat polygons: same primitive, count/height/spacing driven
# up for the "there / after" state so the pair reads as one subject at two
# points in time, not two different objects.


def blade_polygon(
    x: float, y_base: float, height: float, width: float, lean: float
) -> list[tuple[float, float]]:
    """One tapered, slightly leaning blade: wide at the base, narrowing to
    a point at the tip, bending toward `lean` as it rises (matches the
    curved blade tips visible in wheat_stage7)."""
    segs = 5
    left: list[tuple[float, float]] = []
    right: list[tuple[float, float]] = []
    for i in range(segs + 1):
        t = i / segs
        w = width * (1 - t) * 0.5
        dx = lean * height * (t**1.6)
        px, py = x + dx, y_base - height * t
        left.append((px - w, py))
        right.append((px + w, py))
    return left + list(reversed(right))


def draw_blade_tuft(
    d: ImageDraw.ImageDraw,
    cx: float,
    y_base: float,
    n: int,
    height: float,
    height_jitter: float,
    width: float,
    spread: float,
    fig: Color,
    accent: Color,
    accent_every: int = 3,
) -> None:
    """A cluster of n blades on a shared baseline - the one subject, drawn
    once as a sparse short tuft (the 'before' state) or a dense tall tuft
    (the 'after' state), never as a single blade alone."""
    for i in range(n):
        frac = (i - (n - 1) / 2) / max(n - 1, 1)
        x = cx + frac * spread
        h = height * (1 + height_jitter * math.sin(i * 2.1 + 0.6))
        lean = frac * 0.22
        col = accent if (accent_every and i % accent_every == 1) else fig
        pts = blade_polygon(x, y_base, h, width, lean)
        d.polygon([P(px, py) for px, py in pts], fill=col)


def draw_sprout(
    d: ImageDraw.ImageDraw, cx: float, y_base: float, s: float, fig: Color
) -> None:
    """The 'before' state: 3 short, sparse blades (wheat_stage0)."""
    draw_blade_tuft(
        d,
        cx,
        y_base,
        n=3,
        height=s * 0.55,
        height_jitter=0.22,
        width=s * 0.30,
        spread=s * 0.85,
        fig=fig,
        accent=fig,
        accent_every=0,
    )


def draw_grown(
    d: ImageDraw.ImageDraw,
    cx: float,
    y_base: float,
    s: float,
    fig: Color,
    accent: Color,
) -> None:
    """The 'after' state: 7 tall blades, packed tighter, two-tone
    (wheat_stage7) - same tuft primitive as draw_sprout, pushed up in
    count/height/density so the size jump itself reads as 'progressed'."""
    draw_blade_tuft(
        d,
        cx,
        y_base,
        n=7,
        height=s * 1.55,
        height_jitter=0.16,
        width=s * 0.24,
        spread=s * 1.05,
        fig=fig,
        accent=accent,
        accent_every=3,
    )


# =============================================================== A series ===


def cand_A1_split_seed_grown() -> Image.Image:
    """Vertical split panel, a bold gutter at the seam (comic-panel
    convention: 'meanwhile, elsewhere'). Left = here, the sprout as left.
    Right = there, the same plant grown, unattended. Both tufts share one
    baseline (same ground, two points in time). Read left-to-right, the
    size jump IS the progress; nothing in the frame is mid-motion."""
    split = 0.44
    img = vsplit_field(HERE_BG, THERE_BG, split=split)
    d = ImageDraw.Draw(img)
    gutter_w = 0.03
    d.rectangle(
        [P(split - gutter_w / 2, 0), P(split + gutter_w / 2, 1)], fill=DARK_FIELD
    )
    draw_sprout(d, 0.20, 0.80, 0.42, HERE_FIG)
    draw_grown(d, 0.72, 0.80, 0.30, THERE_FIG, THERE_ACCENT)
    return finish(img)


def cand_A2_inset_peek() -> Image.Image:
    """One field (there/advanced), one big grown plant filling most of the
    frame - and a small framed inset in the corner holding the seed it
    was when it was last seen. The inset is a window back to before you
    left, not a thing in motion: exactly the 'you weren't there' read."""
    img = flat_field(THERE_BG)
    d = ImageDraw.Draw(img)
    draw_grown(d, 0.5, 0.89, 0.52, THERE_FIG, THERE_ACCENT)
    # inset window, bottom-left, its own flat field + hard border, drawn
    # on top - a picture-in-picture deliberately overlaps the main image
    ix0, iy0, ix1, iy1 = 0.05, 0.60, 0.38, 0.93
    border = 0.014
    d.rectangle(
        [P(ix0 - border, iy0 - border), P(ix1 + border, iy1 + border)], fill=DARK_FIELD
    )
    d.rectangle([P(ix0, iy0), P(ix1, iy1)], fill=HERE_BG)
    draw_sprout(d, (ix0 + ix1) / 2, iy1 - 0.05, 0.26, HERE_FIG)
    return finish(img)


def cand_A3_stacked_frames() -> Image.Image:
    """Horizontal two-panel comic strip (varies the split axis from A1, per
    the direction doc's 'differ from unloaded-activity's split, don't
    imitate'). Top = here, small. Bottom = there, grown - read top-to-
    bottom as time passing while away."""
    split = 0.42
    img = hsplit_field(HERE_BG, THERE_BG, split=split)
    d = ImageDraw.Draw(img)
    gutter_h = 0.032
    d.rectangle(
        [P(0, split - gutter_h / 2), P(1, split + gutter_h / 2)], fill=DARK_FIELD
    )
    draw_sprout(d, 0.50, 0.40 - gutter_h, 0.34, HERE_FIG)
    draw_grown(d, 0.50, 0.94, 0.32, THERE_FIG, THERE_ACCENT)
    return finish(img)


# =============================================================== D series ===


def cand_D1_weight_shift() -> Image.Image:
    """The word alone, on a dark brand-strong field (architectury's
    vocabulary). 'MEAN' set small, thin, desaturated slate - 'WHILE' set
    large, bold, vivid gold. The letterforms' own weight/scale/colour
    carry the before/after, not a colour-field trick behind plain text
    (the two prior wordmark rounds' failure)."""
    img = flat_field(DARK_FIELD)
    d = ImageDraw.Draw(img)
    max_w = SIDE * 0.80
    f_big, w2, h2 = fit_font(d, "WHILE", "segoeuib.ttf", max_w, 0.30)
    f_small, w1, h1 = fit_font(d, "MEAN", "segoeuisl.ttf", w2 * 0.66, 0.17)
    total_h = h1 + h2 + SIDE * 0.03
    y0 = (SIDE - total_h) / 2
    x0 = (SIDE - w2) / 2
    d.text((x0, y0), "MEAN", font=f_small, fill=SLATE_TEXT)
    d.text((x0, y0 + h1 + SIDE * 0.05), "WHILE", font=f_big, fill=GOLD_TEXT)
    return finish(img)


def cand_D2_stamp_echo() -> Image.Image:
    """The same word printed twice on a light field, like a sign repainted
    after fading: a pale, thin 'before' impression above a solid, bold
    'after' impression below, divided by one flat rule. Two static
    impressions, not one word in motion."""
    img = flat_field(CREAM_FIELD)
    d = ImageDraw.Draw(img)
    max_w = SIDE * 0.84
    f_bot, w2, h2 = fit_font(d, "MEANWHILE", "segoeuib.ttf", max_w, 0.185)
    f_top, w1, h1 = fit_font(d, "MEANWHILE", "segoeuil.ttf", w2 * 0.86, 0.155)
    cx = SIDE / 2
    top_y = SIDE * 0.28
    bot_y = SIDE * 0.58
    d.text((cx - w1 / 2, top_y), "MEANWHILE", font=f_top, fill=FADED_TEXT)
    d.line([P(0.16, 0.50), P(0.84, 0.50)], fill=DARK_TEXT, width=int(SIDE * 0.012))
    d.text((cx - w2 / 2, bot_y), "MEANWHILE", font=f_bot, fill=DARK_TEXT)
    return finish(img)


# ============================================================ A+D fusions ===


def cand_AD1_caption_split() -> Image.Image:
    """The split field (here/slate left, there/amber right) carries the
    2-item pair on its own now: a small paired tuft, low in each half, at
    roughly A1's own scale (condition 2 needs the pair to actually
    progress, a colour split alone does not show that; condition 4 needs
    the finer term - the sparse tuft - to still be there at 48px, which
    failed at the smaller size this file first tried). A flat caption
    box, the classic 'Meanwhile...' comic device, straddles the seam
    above them, moved up so it does not compete with the tufts for
    vertical room."""
    img = vsplit_field(HERE_BG, THERE_BG, split=0.5)
    d = ImageDraw.Draw(img)

    f, w, h = fit_font(d, "MEANWHILE", "segoeuib.ttf", SIDE * 0.72, 0.135)
    pad_x, pad_y = SIDE * 0.045, SIDE * 0.035
    bx0 = SIDE / 2 - w / 2 - pad_x
    bx1 = SIDE / 2 + w / 2 + pad_x
    by0 = SIDE * 0.16
    by1 = by0 + h + pad_y * 2
    d.rectangle([bx0, by0, bx1, by1], fill=CREAM_FIELD)
    # small tail, pointing down-left, part of the same flat shape
    d.polygon(
        [
            (bx0 + w * 0.12, by1),
            (bx0 + w * 0.12 - SIDE * 0.05, by1 + SIDE * 0.07),
            (bx0 + w * 0.28, by1),
        ],
        fill=CREAM_FIELD,
    )
    d.text((SIDE / 2 - w / 2, by0 + pad_y * 0.6), "MEANWHILE", font=f, fill=DARK_TEXT)

    draw_sprout(d, 0.20, 0.88, 0.32, HERE_FIG)
    draw_grown(d, 0.78, 0.88, 0.30, THERE_FIG, THERE_ACCENT)
    return finish(img)


def cand_AD2_gutter_word() -> Image.Image:
    """Two panels (a sparse tuft for here, a full tuft for there) and the
    word itself set vertically along the seam, doing the gutter's job -
    the word IS the panel border, not a label next to it."""
    img = vsplit_field(HERE_BG, THERE_BG, split=0.5)
    d = ImageDraw.Draw(img)
    draw_sprout(d, 0.20, 0.80, 0.34, HERE_FIG)
    draw_grown(d, 0.78, 0.80, 0.26, THERE_FIG, THERE_ACCENT)

    txt_img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    td = ImageDraw.Draw(txt_img)
    f, w, h = fit_font(td, "MEANWHILE", "segoeuib.ttf", SIDE * 0.84, 0.115)
    td.text((SIDE / 2 - w / 2, SIDE / 2 - h / 2), "MEANWHILE", font=f, fill=DARK_FIELD)
    txt_img = txt_img.rotate(90, expand=False, resample=Image.BICUBIC)

    gutter_w = 0.15
    d.rectangle([P(0.5 - gutter_w / 2, 0), P(0.5 + gutter_w / 2, 1)], fill=CREAM_FIELD)
    img.alpha_composite(txt_img)
    return finish(img)


# ============================================================= measurement ===


def measure_mark(img: Image.Image, ink_colours: list[Color], tol: int = 40) -> dict:
    """bbox / margins / fill% / connected-component count of the drawn
    MARK only (the exact palette colours passed in - never 'whatever
    differs from one corner pixel', which breaks on split-field
    candidates with two large same-status regions). Same method as the
    anchor measurement below, so the two are comparable: a tolerance
    band around each known fill colour, not a single reference colour."""
    arr = np.array(img.convert("RGB")).astype(np.int16)
    w, h = img.size
    mask = np.zeros((h, w), dtype=bool)
    for c in ink_colours:
        target = np.array(c[:3], dtype=np.int16)
        mask |= np.abs(arr - target[None, None, :]).sum(axis=2) < tol
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return {
            "bbox_w": 0.0,
            "bbox_h": 0.0,
            "margins": (0, 0, 0, 0),
            "fill": 0.0,
            "components": 0,
        }
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    from scipy import ndimage

    lbl, n = ndimage.label(mask)
    sizes = ndimage.sum(mask, lbl, range(1, n + 1))
    sig = int((sizes > w * h * 0.001).sum())
    return {
        "bbox_w": (x1 - x0 + 1) / w * 100,
        "bbox_h": (y1 - y0 + 1) / h * 100,
        "margins": (
            x0 / w * 100,
            (w - 1 - x1) / w * 100,
            y0 / h * 100,
            (h - 1 - y1) / h * 100,
        ),
        "fill": mask.sum() / (w * h) * 100,
        "components": sig,
    }


# ink colours per candidate: the drawn MARK, never the flat field colours
# (HERE_BG/THERE_BG/DARK_FIELD/CREAM_FIELD alone are backgrounds, not marks)
CANDIDATES: dict[str, tuple] = {
    "A1_split_seed_grown": (
        cand_A1_split_seed_grown,
        [HERE_FIG, THERE_FIG, THERE_ACCENT],
    ),
    "A2_inset_peek": (cand_A2_inset_peek, [HERE_FIG, THERE_FIG, THERE_ACCENT]),
    "A3_stacked_frames": (cand_A3_stacked_frames, [HERE_FIG, THERE_FIG, THERE_ACCENT]),
    "D1_weight_shift": (cand_D1_weight_shift, [SLATE_TEXT, GOLD_TEXT]),
    "D2_stamp_echo": (cand_D2_stamp_echo, [FADED_TEXT, DARK_TEXT]),
    "AD1_caption_split": (
        cand_AD1_caption_split,
        [DARK_TEXT, CREAM_FIELD, HERE_FIG, THERE_FIG, THERE_ACCENT],
    ),
    "AD2_gutter_word": (
        cand_AD2_gutter_word,
        [DARK_FIELD, CREAM_FIELD, HERE_FIG, THERE_FIG, THERE_ACCENT],
    ),
}

# platform card colours the contact sheet pastes each icon onto
CF_WHITE: Color = (255, 255, 255, 255)
MODRINTH_DARK: Color = (30, 31, 34, 255)  # #1e1f22


def build_sheet(images: dict[str, Image.Image]) -> Image.Image:
    """512 on white + on Modrinth-dark, and a 48px inset of the same pair,
    laid out one row per candidate - the 48px legibility check (condition
    4) done visually, not assumed."""
    cell = 220
    inset_48 = 48
    row_h = cell + 40
    label_h = 34
    W = 40 + 4 * (cell + 20) + 300
    H = label_h + len(images) * row_h + 20
    sheet = Image.new("RGB", (W, H), (245, 245, 247))
    d = ImageDraw.Draw(sheet)
    try:
        lf = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22)
    except OSError:
        lf = ImageFont.load_default()

    y = label_h
    for name, img in images.items():
        x = 20
        d.text((x, y + cell // 2 - 12), name, font=lf, fill=(20, 20, 20))
        x = 300
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
    print(
        f"{'name':<22} {'bbox_w':>7} {'bbox_h':>7} {'marginL':>8} {'marginR':>8}"
        f" {'marginT':>8} {'marginB':>8} {'fill%':>7} {'parts':>6}"
    )
    rendered: dict[str, Image.Image] = {}
    for name, (fn, ink_colours) in CANDIDATES.items():
        img = fn()
        img.save(OUT_DIR / f"{name}_512.png")
        rendered[name] = img
        m = measure_mark(img, ink_colours)
        ml, mr, mt, mb = m["margins"]
        print(
            f"{name:<22} {m['bbox_w']:>6.1f}% {m['bbox_h']:>6.1f}% {ml:>7.1f}%"
            f" {mr:>7.1f}% {mt:>7.1f}% {mb:>7.1f}% {m['fill']:>6.1f}% {m['components']:>6}"
        )
    sheet = build_sheet(rendered)
    sheet.save(OUT_DIR / "CONTACT_SHEET_r13.png")
    print(f"wrote {len(CANDIDATES)} candidates + contact sheet to {OUT_DIR}")


if __name__ == "__main__":
    main()
