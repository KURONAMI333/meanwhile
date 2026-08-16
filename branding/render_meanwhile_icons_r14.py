# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 14.

kura's read of r13: the A-series devices (split / stacked / inset /
caption-split) and D1's wordmark are fine, but r13's hand-drawn blade
tuft reads as "spike/thorn/flame" as much as "crop" - the self-reported
weakness. kura's fix: use the real vanilla growth-stage textures
instead of an invented silhouette. Every player has seen
wheat_stage0.png next to wheat_stage7.png; there is no reading it any
other way, and unlike a drawn tuft it needs no interpretation. It is
also literally the mechanic: crops do not advance while their chunk is
unloaded, which is the exact thing this mod fixes.

Source textures (opened and bbox-measured before use, not eyeballed):
  _tools/MineTexture/tool_data/cache/vanilla_textures/block/
    wheat_stage0.png      16x16, content bbox (2,13)-(11,15) = 10x3 px,
                           only 7 non-transparent pixels - a handful of
                           thin green shoots. This sparseness is this
                           round's hard problem: see measure_r14_48px.py
                           for how much it survives at 48px.
    wheat_stage7.png      16x16, content bbox (0,0)-(15,15) = full tile,
                           139 non-transparent pixels - dense gold heads.
    beetroots_stage0.png  16x16, content bbox (2,12)-(12,15) = 11x4 px,
                           15 non-transparent pixels.
    beetroots_stage3.png  16x16, content bbox (0,6)-(15,15) = 16x10 px,
                           116 non-transparent pixels, includes visible
                           red bulbs (a second colour cue wheat doesn't
                           have).

Technique: crop each texture to its own non-transparent bbox (don't
pay panel space for empty canvas), scale up by an INTEGER factor with
NEAREST (no blur on real pixel art), paste onto the SIDE=2048
supersample canvas, and let the one LANCZOS resize at the end (finish())
do all the antialiasing - never resample the sprite itself. Devices are
r13's, unchanged (kura did not reject them): split (A1), stacked (A3),
inset (A2), caption-split (AD1). D1_weight_shift is not remade; r13's
file is copied into this round's folder as a labelled reference so kura
can compare on one sheet.
"""

from __future__ import annotations

import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "icon-candidates-r14"
OUT_DIR.mkdir(exist_ok=True)
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
OUT = 512
Color = tuple[int, int, int, int]

# ---------------------------------------------------------------- colour ---
HERE_BG: Color = (58, 68, 84, 255)  # #3A4454 cool slate, "here / before"
THERE_BG: Color = (214, 141, 41, 255)  # #D68D29 warm amber, "there / after"

# second ground pair for the beetroot variants (kura: "地色も振る")
HERE_BG2: Color = (36, 58, 46, 255)  # #24382E deep forest, "here / before"
THERE_BG2: Color = (107, 30, 34, 255)  # #6B1E22 dark burgundy, "there / after"

DARK_FIELD: Color = (22, 24, 28, 255)  # #16181C near-black gutter/frame
CREAM_FIELD: Color = (231, 227, 218, 255)  # #E7E3DA caption box
DARK_TEXT: Color = (30, 24, 16, 255)


def P(fx: float, fy: float) -> tuple[float, float]:
    return (fx * SIDE, fy * SIDE)


def flat_field(color: Color) -> Image.Image:
    return Image.new("RGBA", (SIDE, SIDE), color)


def vsplit_field(left: Color, right: Color, split: float = 0.5) -> Image.Image:
    img = Image.new("RGBA", (SIDE, SIDE), left)
    ImageDraw.Draw(img).rectangle([P(split, 0), P(1, 1)], fill=right)
    return img


def hsplit_field(top: Color, bottom: Color, split: float = 0.5) -> Image.Image:
    img = Image.new("RGBA", (SIDE, SIDE), top)
    ImageDraw.Draw(img).rectangle([P(0, split), P(1, 1)], fill=bottom)
    return img


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


def font(name: str, frac: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", int(SIDE * frac))


def text_wh(
    d: ImageDraw.ImageDraw, s: str, f: ImageFont.FreeTypeFont
) -> tuple[float, float]:
    l, t, r, b = d.textbbox((0, 0), s, font=f)
    return r - l, b - t


def fit_font(
    d: ImageDraw.ImageDraw, s: str, name: str, max_w: float, start_frac: float
) -> tuple[ImageFont.FreeTypeFont, float, float]:
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


# --------------------------------------------------------------- sprites ---


def load_sprite_cropped(name: str) -> Image.Image:
    """Open the real vanilla texture and crop to its own non-transparent
    bbox - measured with numpy, not assumed. Raises loudly if the file
    is missing (do not silently substitute)."""
    path = TEX_DIR / name
    if not path.exists():
        raise FileNotFoundError(f"vanilla texture not found: {path}")
    img = Image.open(path).convert("RGBA")
    arr = np.array(img)
    ys, xs = np.where(arr[:, :, 3] > 10)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    return img.crop((int(x0), int(y0), int(x1) + 1, int(y1) + 1))


def place_sprite_nearest(
    dest: Image.Image,
    sprite: Image.Image,
    cx: float,
    bottom_y: float,
    target_w_frac: float,
) -> tuple[int, int, int, int]:
    """Integer-NEAREST-scale `sprite` so its width is close to
    target_w_frac of SIDE, then paste it centred on cx with its bottom
    edge on bottom_y (both in 0..1 canvas fractions - shared baseline,
    same convention as r13's tufts). Returns the pasted (x, y, w, h) in
    SIDE px, so the exact placed rect can be re-checked at 48px later.
    Never resamples with anything but NEAREST."""
    target_px = target_w_frac * SIDE
    scale = max(1, round(target_px / sprite.width))
    scaled = sprite.resize((sprite.width * scale, sprite.height * scale), Image.NEAREST)
    x = int(cx * SIDE - scaled.width / 2)
    y = int(bottom_y * SIDE - scaled.height)
    dest.alpha_composite(scaled, (x, y))
    return x, y, scaled.width, scaled.height


# ==================================================================== A ===


def cand_split_wheat() -> Image.Image:
    """A1's device: vertical split, dark gutter at the seam. Left = here,
    wheat_stage0 (planted, chunk about to unload). Right = there,
    wheat_stage7 (grown, unattended)."""
    split = 0.44
    img = vsplit_field(HERE_BG, THERE_BG, split=split)
    d = ImageDraw.Draw(img)
    gutter_w = 0.03
    d.rectangle(
        [P(split - gutter_w / 2, 0), P(split + gutter_w / 2, 1)], fill=DARK_FIELD
    )
    s0 = load_sprite_cropped("wheat_stage0.png")
    s7 = load_sprite_cropped("wheat_stage7.png")
    x0, y0, w0, h0 = place_sprite_nearest(img, s0, 0.20, 0.86, target_w_frac=0.30)
    x7, y7, w7, h7 = place_sprite_nearest(img, s7, 0.72, 0.90, target_w_frac=0.42)
    return finish(img), {
        "wheat_stage0": (x0, y0, w0, h0),
        "wheat_stage7": (x7, y7, w7, h7),
    }


def cand_stacked_wheat() -> Image.Image:
    """A3's device: horizontal split, gutter bar. Top = here, small.
    Bottom = there, grown - read top-to-bottom as time passing while
    away. Ground colours varied per kura's instruction."""
    split = 0.40
    # a second ground pair, varied from split_wheat's slate/amber: muted
    # sage top ("here/before"), deep umber-brown bottom ("there/after")
    img = hsplit_field((90, 104, 74, 255), (58, 40, 22, 255), split=split)
    d = ImageDraw.Draw(img)
    gutter_h = 0.032
    d.rectangle(
        [P(0, split - gutter_h / 2), P(1, split + gutter_h / 2)], fill=DARK_FIELD
    )
    s0 = load_sprite_cropped("wheat_stage0.png")
    s7 = load_sprite_cropped("wheat_stage7.png")
    x0, y0, w0, h0 = place_sprite_nearest(img, s0, 0.50, 0.38, target_w_frac=0.30)
    x7, y7, w7, h7 = place_sprite_nearest(img, s7, 0.50, 0.95, target_w_frac=0.46)
    return finish(img), {
        "wheat_stage0": (x0, y0, w0, h0),
        "wheat_stage7": (x7, y7, w7, h7),
    }


def cand_inset_wheat() -> Image.Image:
    """A2's device: one big field with the grown wheat filling most of the
    frame, and a small framed inset in the corner holding stage0 - what
    it looked like when it was last seen (the 'you weren't there' read)."""
    img = flat_field(THERE_BG)
    d = ImageDraw.Draw(img)
    s7 = load_sprite_cropped("wheat_stage7.png")
    x7, y7, w7, h7 = place_sprite_nearest(img, s7, 0.55, 0.92, target_w_frac=0.64)

    ix0, iy0, ix1, iy1 = 0.05, 0.60, 0.38, 0.93
    border = 0.014
    d.rectangle(
        [P(ix0 - border, iy0 - border), P(ix1 + border, iy1 + border)], fill=DARK_FIELD
    )
    d.rectangle([P(ix0, iy0), P(ix1, iy1)], fill=HERE_BG)
    s0 = load_sprite_cropped("wheat_stage0.png")
    x0, y0, w0, h0 = place_sprite_nearest(
        img, s0, (ix0 + ix1) / 2, iy1 - 0.04, target_w_frac=0.24
    )
    return finish(img), {
        "wheat_stage0": (x0, y0, w0, h0),
        "wheat_stage7": (x7, y7, w7, h7),
    }


def cand_caption_split_wheat() -> Image.Image:
    """AD1's device: split field + a 'Meanwhile...' caption box straddling
    the seam + the stage0/stage7 pair low in each half. The one A+D
    fusion this round, as instructed."""
    img = vsplit_field(HERE_BG, THERE_BG, split=0.5)
    d = ImageDraw.Draw(img)

    f, w, h = fit_font(d, "MEANWHILE", "segoeuib.ttf", SIDE * 0.72, 0.135)
    pad_x, pad_y = SIDE * 0.045, SIDE * 0.035
    bx0 = SIDE / 2 - w / 2 - pad_x
    bx1 = SIDE / 2 + w / 2 + pad_x
    by0 = SIDE * 0.16
    by1 = by0 + h + pad_y * 2
    d.rectangle([bx0, by0, bx1, by1], fill=CREAM_FIELD)
    d.polygon(
        [
            (bx0 + w * 0.12, by1),
            (bx0 + w * 0.12 - SIDE * 0.05, by1 + SIDE * 0.07),
            (bx0 + w * 0.28, by1),
        ],
        fill=CREAM_FIELD,
    )
    d.text((SIDE / 2 - w / 2, by0 + pad_y * 0.6), "MEANWHILE", font=f, fill=DARK_TEXT)

    s0 = load_sprite_cropped("wheat_stage0.png")
    s7 = load_sprite_cropped("wheat_stage7.png")
    x0, y0, w0, h0 = place_sprite_nearest(img, s0, 0.20, 0.90, target_w_frac=0.28)
    x7, y7, w7, h7 = place_sprite_nearest(img, s7, 0.78, 0.93, target_w_frac=0.38)
    return finish(img), {
        "wheat_stage0": (x0, y0, w0, h0),
        "wheat_stage7": (x7, y7, w7, h7),
    }


def cand_split_beetroot() -> Image.Image:
    """Same split device as cand_split_wheat, different crop and ground
    colours (kura: try a crop other than wheat too, vary the field).
    Beetroot's stage3 carries a second colour cue wheat doesn't - visible
    red bulbs - on top of the size/density jump."""
    split = 0.44
    img = vsplit_field(HERE_BG2, THERE_BG2, split=split)
    d = ImageDraw.Draw(img)
    gutter_w = 0.03
    d.rectangle(
        [P(split - gutter_w / 2, 0), P(split + gutter_w / 2, 1)], fill=DARK_FIELD
    )
    s0 = load_sprite_cropped("beetroots_stage0.png")
    s3 = load_sprite_cropped("beetroots_stage3.png")
    x0, y0, w0, h0 = place_sprite_nearest(img, s0, 0.20, 0.86, target_w_frac=0.30)
    x3, y3, w3, h3 = place_sprite_nearest(img, s3, 0.72, 0.90, target_w_frac=0.42)
    return finish(img), {
        "beetroots_stage0": (x0, y0, w0, h0),
        "beetroots_stage3": (x3, y3, w3, h3),
    }


def cand_stacked_beetroot() -> Image.Image:
    """Same stacked device as cand_stacked_wheat, beetroot crop, a third
    ground pairing (muted moss / near-black-burgundy)."""
    split = 0.40
    img = hsplit_field((84, 92, 70, 255), (46, 20, 22, 255), split=split)
    d = ImageDraw.Draw(img)
    gutter_h = 0.032
    d.rectangle(
        [P(0, split - gutter_h / 2), P(1, split + gutter_h / 2)], fill=DARK_FIELD
    )
    s0 = load_sprite_cropped("beetroots_stage0.png")
    s3 = load_sprite_cropped("beetroots_stage3.png")
    x0, y0, w0, h0 = place_sprite_nearest(img, s0, 0.50, 0.38, target_w_frac=0.30)
    x3, y3, w3, h3 = place_sprite_nearest(img, s3, 0.50, 0.95, target_w_frac=0.46)
    return finish(img), {
        "beetroots_stage0": (x0, y0, w0, h0),
        "beetroots_stage3": (x3, y3, w3, h3),
    }


CANDIDATES = {
    "split_wheat": cand_split_wheat,
    "stacked_wheat": cand_stacked_wheat,
    "inset_wheat": cand_inset_wheat,
    "caption_split_wheat": cand_caption_split_wheat,
    "split_beetroot": cand_split_beetroot,
    "stacked_beetroot": cand_stacked_beetroot,
}

CF_WHITE: Color = (255, 255, 255, 255)
MODRINTH_DARK: Color = (30, 31, 34, 255)


def measure_sparse_survival(
    img512: Image.Image,
    placed_rect_side_px: tuple[int, int, int, int],
    ref_field: Color,
    tol: int = 30,
    pad_frac: float = 0.35,
) -> tuple[int, int, int]:
    """The round's hard question, answered precisely: crop the 48x48
    render to (a small pad around) the exact rect the sparse sprite
    (stage0 / beetroot stage0) was pasted at - in SIDE=2048 px, from
    place_sprite_nearest's own return value, converted to 48px
    coordinates - and count pixels there that are not within `tol` of
    the surrounding flat field colour. Returns
    (non_field_px, crop_w_48px, crop_h_48px) so a 0 is legible as "the
    crop was N px and 0 of them survived", not just a bare zero."""
    x, y, w, h = placed_rect_side_px
    pad_x, pad_y = int(w * pad_frac), int(h * pad_frac)
    bx0 = max(0, x - pad_x)
    by0 = max(0, y - pad_y)
    bx1 = min(SIDE, x + w + pad_x)
    by1 = min(SIDE, y + h + pad_y)
    small_full = img512.resize((48, 48), Image.LANCZOS).convert("RGB")
    # map the SIDE-px crop rect to the 48px canvas
    s = 48 / SIDE
    cx0, cy0 = int(bx0 * s), int(by0 * s)
    cx1, cy1 = max(cx0 + 1, int(bx1 * s)), max(cy0 + 1, int(by1 * s))
    crop = small_full.crop((cx0, cy0, cx1, cy1))
    arr = np.array(crop).astype(np.int16)
    ref = np.array(ref_field[:3], dtype=np.int16)
    diff = np.abs(arr - ref[None, None, :]).sum(axis=2)
    return int((diff > tol).sum()), crop.width, crop.height


def build_sheet(rows: list[tuple[str, Image.Image]]) -> Image.Image:
    cell = 220
    inset_48 = 48
    row_h = cell + 40
    label_h = 34
    W = 40 + 4 * (cell + 20) + 300
    H = label_h + len(rows) * row_h + 20
    sheet = Image.new("RGB", (W, H), (245, 245, 247))
    d = ImageDraw.Draw(sheet)
    try:
        lf = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22)
    except OSError:
        lf = ImageFont.load_default()

    y = label_h
    for name, img in rows:
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
    print("sprite placement (integer NEAREST scale, px at SIDE=2048):")
    rendered: list[tuple[str, Image.Image]] = []
    all_placements: dict[str, dict] = {}
    for name, fn in CANDIDATES.items():
        img, placements = fn()
        img.save(OUT_DIR / f"{name}_512.png")
        rendered.append((name, img))
        all_placements[name] = placements
        for tex, (x, y, w, h) in placements.items():
            print(
                f"  {name:<22} {tex:<22} placed {w}x{h}px  ({w/SIDE*100:.1f}% of side)"
            )

    # bring r13's D1 in unchanged, as a labelled reference row
    d1_src = ROOT / "icon-candidates-r13" / "D1_weight_shift_512.png"
    if d1_src.exists():
        d1_dst = OUT_DIR / "D1_weight_shift_REFERENCE_r13_512.png"
        shutil.copyfile(d1_src, d1_dst)
        rendered.append(
            ("D1_weight_shift (r13 reference, unchanged)", Image.open(d1_dst))
        )

    sheet = build_sheet(rendered)
    sheet.save(OUT_DIR / "CONTACT_SHEET_r14.png")

    # the sparse ("before") sprite per candidate, and the field colour it
    # sits on - the pair measure_sparse_survival needs to isolate exactly
    # that sprite's own 48px crop, not the whole icon
    sparse_key = {
        "split_wheat": ("wheat_stage0", HERE_BG),
        "stacked_wheat": ("wheat_stage0", (90, 104, 74, 255)),
        "inset_wheat": ("wheat_stage0", HERE_BG),
        "caption_split_wheat": ("wheat_stage0", HERE_BG),
        "split_beetroot": ("beetroots_stage0", HERE_BG2),
        "stacked_beetroot": ("beetroots_stage0", (84, 92, 70, 255)),
    }
    print("\nstage0/beetroot-stage0 ('before') 48px survival - isolated crop:")
    for name, img in rendered:
        if name not in sparse_key:
            continue
        tex, ref_field = sparse_key[name]
        rect = all_placements[name][tex]
        n_px, cw, ch = measure_sparse_survival(img, rect, ref_field)
        verdict = "SURVIVES" if n_px > 0 else "GONE"
        print(
            f"  {name:<22} {tex:<18} crop {cw}x{ch}px @48  non-field px = {n_px:>3}  [{verdict}]"
        )

    print(
        f"\nwrote {len(CANDIDATES)} candidates + reference + contact sheet to {OUT_DIR}"
    )


if __name__ == "__main__":
    main()
