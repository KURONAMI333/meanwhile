# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 1 candidates.

Classification (LOGO_PLAYBOOK): (1) original mod. It is not an addon to a host
mod and not part of the Isekai series, so the Subject-Max grammar applies - with
one caveat that has to be said out loud: Subject-Max rule 1 asks for pixel art of
"the mod's representative block or item", and this mod registers none. It is
server-side, has no block, item, screen or asset directory. So rule 1 is
inapplicable and the candidates below sit in the grammars that the shelf
measurement actually turned up (see step 0), not in a rule that has nothing to
point at.

Step 0 (measured 2026-08-12, Modrinth, three cohorts kept apart):

  A direct function   anabiosis (58 dl) - the one mod doing this job. Dark navy
                      field, pixel-art HOURGLASS, bbox 38%. The obvious "time
                      passing" image is already taken by the direct competitor.
  B chunk loaders     n=10, bbox median 100% - isometric block renders, full
                      bleed. The genre players reach for instead of this mod.
  C the actual shelf  n=24, server-side utility/optimization on 1.21.1 by
                      downloads, bbox median 77%. Registers present: one bold
                      silhouette on a plain field (lithium 25%, modernfix 79%,
                      sound-physics 76%, yacl 50%, simple-voice-chat 59%), baked
                      text (krypton, c2me, moreculling, ecl), and pixel-art
                      objects (clumps, jade, appleskin).

Four candidates, four different claims about the mod. They are deliberately in
four different registers as well, so the sheet compares ideas rather than
colourways:

  A  tally on slate          the missed time is counted, not estimated. The
                             drawing carries "counted"; the repaying is not in it
  B  the lit furnace mouth   you come back and the fire is still going; the work
                             got done while nobody was there. Note that a chunk
                             loader can claim this too - the drawing does not say
                             "without keeping the chunk loaded"
  C  one step, then the rest a single observed step is enough to work out the
                             whole interval - which is why it needs no code
                             written for the machine it is advancing
  D  the grid with one cell  the ground you walked away from is not frozen
     unlit                   behind you; it keeps its own accounts

None of them is a clock, an hourglass or a stopwatch.

Every colour is sampled from something real, per LOGO_PLAYBOOK's "colour comes
from the subject" rule; no colour here was chosen to avoid clashing with another
mod. Sources are named at each constant. The two pixel subjects (B) are cropped
out of the vanilla PNG itself rather than drawn from memory.

SS=4 -> LANCZOS for the vector work; the pixel subject is composited at an
integer NEAREST scale before the whole frame is reduced, per LOGO_PLAYBOOK.
"""

import os

import numpy as np
from PIL import Image, ImageDraw

SS, OUT = 4, 512
N = OUT * SS
HERE = os.path.dirname(os.path.abspath(__file__))
OUTDIR = os.path.join(HERE, "icon-candidates")
os.makedirs(OUTDIR, exist_ok=True)

VANILLA = os.path.join(
    HERE, "..", "..", "_tools", "MineTexture", "tool_data", "cache", "vanilla_textures"
)

RADIUS = 0.20  # LOGO_PLAYBOOK: corner radius 18-22% of the frame

WHITE = (255, 255, 255)
# deepslate.png, mean of the 16x16 - a tally is chalk on slate
SLATE = (80, 80, 83)
# furnace_front_on.png, the darkest stone in the block face
FURNACE_STONE = (60, 59, 59)
# furnace_front_on.png, the flame's mid tone
EMBER = (255, 143, 0)
# ink on paper: near-black, not #000 (negative_list 7)
INK = (26, 25, 24)
PAPER = (247, 246, 243)
# colormap/grass.png sampled at the plains point (temperature .8, downfall .4)
GRASS = (145, 189, 89)
GRASS_DARK = (58, 74, 40)


def px(v):
    """Fraction of the frame -> pixels."""
    return v * N


def field(colour):
    """Flat rounded square in one colour, at supersampled size."""
    big = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle(
        [0, 0, N - 1, N - 1], radius=int(N * RADIUS), fill=tuple(colour) + (255,)
    )
    return big


def corner_mask():
    m = Image.new("L", (N, N), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, N - 1, N - 1], radius=int(N * RADIUS), fill=255
    )
    return m


def box(d, x0, y0, x1, y1, fill, radius=None):
    r = [px(x0), px(y0), px(x1), px(y1)]
    if radius:
        d.rounded_rectangle(r, radius=int(px(radius)), fill=tuple(fill) + (255,))
    else:
        d.rectangle(r, fill=tuple(fill) + (255,))


# --- A: tally on slate --------------------------------------------------------
# The mod's own vocabulary for the ticks a chunk missed is a debt, paid off in
# instalments (pay/drain/forget in the source). A tally is what a debt is kept
# on. Four strokes and the fifth closing them: the count is being settled.
def draw_tally(canvas):
    d = ImageDraw.Draw(canvas)
    top, bot, w = 0.29, 0.71, 0.058
    for i in range(4):
        x = 0.175 + i * 0.152
        box(d, x, top, x + w, bot, WHITE, radius=w * 0.35)
    d.line(
        [(px(0.135), px(0.665)), (px(0.845), px(0.335))],
        fill=WHITE + (255,),
        width=int(px(w)),
        joint="curve",
    )
    return canvas


# --- B: the lit furnace mouth -------------------------------------------------
# Cropped out of the vanilla texture, not drawn from memory. The crop drops the
# stone band across the top and keeps the subject: the arched dark opening, the
# ledge, and the fire under it.
def furnace_subject(scale_frac=0.82):
    """The mouth of furnace_front_on.png, cropped out of the vanilla PNG.

    Columns 3-12 and rows 3-15: the dark arch, the ledge below it and the fire.
    The full 16x16 is mostly stone frame, and blowing all of it up is the
    playbook's "just an enlarged texture" failure, so the frame is cropped away.
    What is left splits about half arch, half fire.
    """
    src = Image.open(os.path.join(VANILLA, "block", "furnace_front_on.png")).convert(
        "RGBA"
    )
    crop = src.crop((3, 3, 13, 16))  # 10 x 13
    k = max(1, int(N * scale_frac / max(crop.width, crop.height)))
    return crop.resize((crop.width * k, crop.height * k), Image.NEAREST)


def draw_furnace(canvas):
    sub = furnace_subject()
    canvas.alpha_composite(sub, ((N - sub.width) // 2, (N - sub.height) // 2))
    return canvas


# --- C: one step, then the rest -----------------------------------------------
# It ticks the block entity once, reads what moved, and repeats that same step
# for the rest of the interval. The first tread is the one that was measured;
# the other three are the same tread, projected.
def draw_steps(canvas):
    d = ImageDraw.Draw(canvas)
    s, x0, ybot = 0.170, 0.155, 0.845
    for i in range(4):
        x = x0 + i * s
        y1 = ybot - i * s
        box(d, x, y1 - s, x + s, y1, EMBER if i == 0 else INK, radius=0.018)
    return canvas


# --- D: the grid with one cell unlit ------------------------------------------
# Chunks seen from above. Every one of them holds something that ticks; one of
# them is the one you are not standing in. It is drawn the same as the others -
# unlit, not empty.
def draw_grid(canvas):
    d = ImageDraw.Draw(canvas)
    cell, gap = 0.222, 0.026
    span = 3 * cell + 2 * gap
    x0 = y0 = (1.0 - span) / 2.0
    unlit_cell = (1, 1)
    for r in range(3):
        for c in range(3):
            x = x0 + c * (cell + gap)
            y = y0 + r * (cell + gap)
            unlit = (c, r) == unlit_cell
            box(
                d,
                x,
                y,
                x + cell,
                y + cell,
                GRASS_DARK if unlit else WHITE,
                radius=0.030,
            )
            if unlit:
                m = cell * 0.36
                box(
                    d,
                    x + (cell - m) / 2,
                    y + (cell - m) / 2,
                    x + (cell + m) / 2,
                    y + (cell + m) / 2,
                    EMBER,
                    radius=0.014,
                )
    return canvas


CANDIDATES = [
    (
        "meanwhile_A_tally",
        SLATE,
        draw_tally,
        "the missed time is counted, not estimated",
    ),
    (
        "meanwhile_B_furnace",
        FURNACE_STONE,
        draw_furnace,
        "you come back and the fire is still going - the work got done",
    ),
    (
        "meanwhile_C_steps",
        PAPER,
        draw_steps,
        "one observed step is enough to work out the whole interval",
    ),
    (
        "meanwhile_D_grid",
        GRASS,
        draw_grid,
        "the ground you walked away from is not frozen behind you",
    ),
]


def render(bg, draw_fn):
    img = draw_fn(field(bg))
    img.putalpha(
        Image.composite(img.getchannel("A"), Image.new("L", (N, N), 0), corner_mask())
    )
    return img.resize((OUT, OUT), Image.LANCZOS)


def metrics(img, bg):
    """(bbox occupancy, subject coverage) - subject = anything off the field.

    The field colour is passed in rather than sampled from a corner: the corners
    lie outside the rounded rectangle and are transparent.
    """
    a = np.asarray(
        img.convert("RGBA").resize((256, 256), Image.LANCZOS), dtype=np.float64
    )
    rgb, al = a[:, :, :3], a[:, :, 3]
    bgc = np.array(bg, dtype=np.float64)
    subject = (al > 24) & (np.sqrt(((rgb - bgc) ** 2).sum(axis=2)) >= 42)
    ys, xs = np.nonzero(subject)
    if len(xs) < 8:
        return 0.0, 0.0
    bbox = ((xs.max() - xs.min() + 1) / 256) * ((ys.max() - ys.min() + 1) / 256)
    return bbox, subject.mean()


if __name__ == "__main__":
    finals = {}
    for name, bg, fn, claim in CANDIDATES:
        img = render(bg, fn)
        for size in (512, 256, 128, 64):
            path = os.path.join(OUTDIR, f"{name}_{size}.png")
            (img if size == 512 else img.resize((size, size), Image.LANCZOS)).save(path)
        finals[name] = img
        bbox, cov = metrics(img, bg)
        print(f"{name:22} bbox {bbox:5.1%}  subject coverage {cov:5.1%}   {claim}")

    print(
        "\nstep 0 reference bands: abstract-function mods 20-50% bbox "
        "(LOGO_PLAYBOOK); shelf cohort C median 77%, range 25-100%; "
        "anabiosis (direct competitor) 38%"
    )

    # --- contact sheet: big tile + 96/64/48 legibility insets -----------------
    TILE, PAD, LABEL = 340, 22, 40
    cols = 2
    rows_n = (len(CANDIDATES) + cols - 1) // cols
    sw = PAD + cols * (TILE + PAD)
    sh = PAD + rows_n * (TILE + LABEL + PAD)
    sheet = Image.new("RGB", (sw, sh), (52, 54, 60))
    sd = ImageDraw.Draw(sheet)

    for i, (name, bg, fn, claim) in enumerate(CANDIDATES):
        t = finals[name]
        cx = PAD + (i % cols) * (TILE + PAD)
        cy = PAD + (i // cols) * (TILE + LABEL + PAD) + LABEL
        checker = Image.new("RGB", (TILE, TILE), (255, 255, 255))
        checker.paste(t.resize((TILE, TILE), Image.LANCZOS).convert("RGB"), (0, 0))
        sheet.paste(checker, (cx, cy))

        x = cx + TILE - 6
        for s in (96, 64, 48):
            ins = t.resize((s, s), Image.LANCZOS).convert("RGB")
            x -= s
            sheet.paste(ins, (x, cy + TILE - s - 6))
            sd.rectangle(
                [x, cy + TILE - s - 6, x + s, cy + TILE - 6], outline=(140, 140, 140)
            )
            x -= 8

        bbox, cov = metrics(t, bg)
        sd.text(
            (cx + 2, cy - 34),
            f"{name}   bbox {bbox:.0%}  coverage {cov:.0%}",
            fill=(238, 238, 238),
        )
        sd.text((cx + 2, cy - 18), claim, fill=(178, 180, 186))

    sheet.save(os.path.join(OUTDIR, "_contact_sheet.png"))
    print("\nsaved", os.path.join(OUTDIR, "_contact_sheet.png"))

    # --- the size the icon is actually seen at, on both list backgrounds ------
    # A store listing shows this at 64px far more often than at 512, and the
    # listing is light on CurseForge and dark on Modrinth.
    ROW, GUT = 64 + 48 + 26, 26
    cw = GUT + len(CANDIDATES) * (64 + GUT)
    check = Image.new("RGB", (cw, 2 * (ROW + GUT) + GUT), (255, 255, 255))
    cd = ImageDraw.Draw(check)
    cd.rectangle([0, ROW + GUT, cw, check.height], fill=(35, 36, 40))
    for band, y0 in ((0, GUT), (1, ROW + 2 * GUT)):
        for i, (name, bg, fn, claim) in enumerate(CANDIDATES):
            t = finals[name]
            x = GUT + i * (64 + GUT)
            check.paste(t.resize((64, 64), Image.LANCZOS).convert("RGB"), (x, y0))
            check.paste(
                t.resize((48, 48), Image.LANCZOS).convert("RGB"), (x + 8, y0 + 64 + 10)
            )
            cd.text(
                (x, y0 + 64 + 48 + 14),
                name.split("_")[1],
                fill=(60, 60, 60) if band == 0 else (215, 215, 215),
            )
    check.save(os.path.join(OUTDIR, "_small_size_check.png"))
    print("saved", os.path.join(OUTDIR, "_small_size_check.png"))
