# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 3 candidates.

Round 1 (four flat abstract marks: tally, furnace-mouth crop, steps, chunk
grid) and round 2 (four illustrated scenes: isometric room, dusk landscape,
lit house at night, the name burned into a field) were both rejected. kura's
verdict on both was the same: they read as belonging to a lightweight/perf
mod, not this one. That verdict was delivered against the frame the icon was
being designed for at the time ("sell this as a lightweight mod"), and that
frame has since been thrown out - Meanwhile is not a performance mod and does
not use speed/lightness imagery (lightning, speed lines, gauges). So "make it
read more lightweight" is not the fix; the frame itself was wrong, and this
round does not chase it.

What round 1 and round 2 share, read again after the fact, is that neither
tried the plain LOGO_PLAYBOOK Subject-Max register as written: **one bold
subject, most of the frame, flat field, high contrast.** Round 1's closest
attempt (candidate B) put a furnace mouth on a field painted almost the same
grey as the furnace itself (FURNACE_STONE bg under a furnace-toned crop) and
scaled it to only 52% bbox - the playbook's own failure mode ("48px で潰れる:
主題が小さい"), self-inflicted by low contrast and timid scale. Round 2 went
the opposite direction: multi-element scenes at high bbox but low subject
clarity (an isometric diorama, a landscape). Neither round put a *single*,
*large*, *high-contrast* subject on a *flat field*, which gate 0 below shows
is exactly what the real shelf rewards most often.

Step 0, redone with a bigger pull than either previous round (2026-08-15,
Modrinth API, three cohorts, 33 icons total, canonical (non-thumbnail)
icon_url resolved per project, bbox measured by flood-filling the field from
all four corners for flat-field icons and by alpha for transparent ones):

  direct competitors   n=3   unloaded-activity 100%, the-block-keeps-ticking
                        100%, offlineworldcontinues 69%          median 100%
  chunk-loader genre    n=10  81-100%                             median 100%
  1.21.1 DL top 20      n=20  50-100% (the "flat field + bold glyph" register
                        sits at the low end here: sodium 58%, lithium 50%,
                        sodium-extra 58%, cloth-config 71%, yacl 75%,
                        architectury-api 67%; everything else in this cohort
                        is full-bleed art or a full-bleed render)   median 94%
  all 33                                                            median 98%

This is a materially different number from round 1's own step-0 read (shelf
cohort median 77%, 26 icons). The gap is the cohort: round 1's 26 were
deliberately curated to "server-side utility/optimization" projects, which
skews toward the flat-field register; this pull adds the direct competitors
and the actual top-20-by-downloads on the version this mod ships for, and
both of those cohorts sit at 100% median. Read plainly: **being timid about
subject scale is not what the shelf does**, in either register. The flat-field
register that exists (sodium/lithium/etc.) still sits at 50-75%, not round 1's
31-52%.

The subject is the "自分で描いた動いている機械" direction the previous
round left untried, kept from the first pass through this round: one machine,
mid-job, on a flat field.

  furnace   the lit arch of a furnace, mid-smelt.
            Claim: the furnace kept cooking the entire time you were gone.
  hopper    a hopper with one ingot caught mid-drop out of its spout.
            Claim: the hopper never stopped feeding what was under it.

Neither is a clock, an hourglass, or a stopwatch (round 2's rule, kept: the
one mod on the shelf doing this job, unloaded-activity, already owns the
hourglass shape in spirit even though its actual icon is a split day/night
scene). Neither is a speed or lightness image.

**What changed from the first pass through this round, on coordinator
review, and why:**

1. The subjects were vector redraws - polygons and rounded rectangles built
   to *resemble* a furnace and a hopper - with colour sampled from the real
   textures but the silhouette itself recalled rather than read off the PNG.
   That is exactly the failure negative_list and LOGO_PLAYBOOK's Subject-Max
   rule 1 name ("実在物の輪郭を記憶で描く" / pixel art of the subject, not a
   redraw of it), and it showed: the furnace read as a hearth or a tunnel
   mouth, not a Minecraft furnace, and the ingot came out rounded, which no
   object in Minecraft is. The fix is below ("texture crops"): every subject
   pixel now comes from the actual PNG, composited at an integer NEAREST
   scale, nothing hand-drawn.
2. The furnace had two pale squares floating above the arch ("sparks") that
   were legible as nothing in particular, worst at 48px. Dropped rather than
   fixed - the real fire pixels in the texture crop already carry the
   "still burning" read on their own.
3. Ink coverage was thin relative to the measured shelf (furnace 56%, hopper
   27% against a shelf where even the "flat field + bold glyph" register
   sits at 50-75% bbox and everything else sits higher). The fix is not
   "make the same shapes bigger" - it is: use a *square* furnace crop rather
   than the narrower 10x13 an earlier attempt used, so a bbox target
   translates straight into that fraction of *area* instead of losing area
   to the crop's own aspect ratio; and butt the hopper and the ingot
   together with no gap, so the pair reads and measures as one shape.
   Targets were also pushed from 80/90% bbox to 85/92%.

Colour is still taken from the subject rather than chosen to avoid a clash
(LOGO_PLAYBOOK, 2026-07-30 addendum) - that half of the first pass survives
unchanged, including the oxidised-copper field for E (its "sat untouched"
meaning is on-theme, not a colour picked for its own sake).

Texture crops: the exact vanilla file and rectangle each subject's pixels
come from are documented beside FURNACE_CROP_BOX, HOPPER_SRC and INGOT_SRC
below, together with the reasoning for each boundary. Geometry is otherwise
unchanged: SS=4 (2048px composite -> 512 LANCZOS), with every subject placed
at an integer NEAREST factor before that final reduction, per
LOGO_PLAYBOOK's own rule for pixel-art elements. The field (the rounded flat
square, or nothing for the transparent candidates) is still ordinary
vector fill - only the *subject* was recalled-from-memory before, and only
the subject changes here.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- canvas ----

SS = 4
OUT = 512
SIDE = OUT * SS

BRANDING = Path(__file__).resolve().parent
CAND = BRANDING / "icon-candidates-r3"
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


BLACK = hexc("#000000")

# ------------------------------------------------------------- palettes ----
# Field colours only - round 3 v2 no longer draws the subjects as vector
# shapes (see "texture crops" below), so there is nothing left to give a
# hand-picked subject colour to. Every field is still mixed from a vanilla
# sample rather than picked freestyle (LOGO_PLAYBOOK, "colour is taken from
# the subject").

# furnace_front_on.png, the fire's low tone
FIRE_LOW = hexc("#b13f00")
# coal_block.png, for the charcoal field
COAL = hexc("#151515")
# raw_iron_block.png / copper_block.png, for the hopper's warm field
COPPER = hexc("#c87456")
# oxidized_copper.png - the patina a block gets from sitting untouched, which
# is the one field colour here chosen for its meaning rather than sampled
# straight off the subject
PATINA = hexc("#59b292")

# fields, mixed from the constants above rather than picked freestyle
FIELD_RUST = mix(FIRE_LOW, BLACK, 0.30)  # furnace field A: the fire, banked down
FIELD_CHARCOAL = mix(
    COAL, FIRE_LOW, 0.22
)  # furnace field B: soot with a coal still in it
FIELD_COPPER = mix(COPPER, BLACK, 0.12)  # hopper field A: its own cargo's metal, warmed
FIELD_PATINA = mix(PATINA, BLACK, 0.08)  # hopper field B: the untouched-block patina

# ------------------------------------------------------------- drawing -----


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


# ========================================================= texture crops ===
# round 3 v2: the subject is real vanilla pixels, not a vector redraw. A
# vector furnace read as a hearth/tunnel and a vector ingot came out rounded
# (Minecraft has no rounded objects) - both are "the outline of a real thing,
# drawn from memory", the exact failure LOGO_PLAYBOOK and negative_list name.
# So every shape below is loaded from the actual PNG and placed at an integer
# NEAREST scale; nothing here is a polygon.
#
# FURNACE_CROP_BOX trims block/furnace_front_on.png (16x16, opaque, no alpha)
# down from the full tile to arch + ledge + fire - using the full 16x16 is
# the failure table's "just an enlarged texture" (round 1's own warning).
# (1, 2, 15, 16) was chosen over the narrower crop an earlier pass in this
# round used (3, 3, 13, 16), because at 10x13 it left the subject's own
# fill-ratio-times-bbox coverage low once padded to a square frame; at 14x14
# it is square, so a bbox target translates straight into that same fraction
# of *area*, not less.
FURNACE_CROP_BOX = (1, 2, 15, 16)  # 14x14, fill ratio 100% (opaque source tile)

# item/hopper.png is the baked isometric icon Minecraft itself shows for a
# hopper (not the flat block-face tile, which is a repeating grey pattern
# with no funnel silhouette in 2D - checked and rejected before writing this).
# getbbox() trims its own transparent margin.
HOPPER_SRC = "item/hopper.png"

# item/gold_ingot.png is the actual ingot sprite - the "still moving cargo"
# accent below the hopper's spout. Gold over iron because a first pass used
# iron's pale grey (#e6e6e6) and it disappeared into every field at 48px.
INGOT_SRC = "item/gold_ingot.png"


def load_crop(
    rel_path: str, box: tuple[int, int, int, int] | None = None
) -> Image.Image:
    img = Image.open(VANILLA / rel_path).convert("RGBA")
    return img.crop(box) if box else img.crop(img.getbbox())


def place_nearest(
    dest: Image.Image, sprite: Image.Image, k: int, cx: int, cy: int
) -> tuple[int, int, int, int]:
    """NEAREST-scale `sprite` by the integer factor k and paste it centred at
    (cx, cy) in the destination's own pixel space. Returns the placed box."""
    scaled = sprite.resize((sprite.width * k, sprite.height * k), Image.NEAREST)
    x0, y0 = cx - scaled.width // 2, cy - scaled.height // 2
    dest.alpha_composite(scaled, (x0, y0))
    return (x0, y0, scaled.width, scaled.height)


# ============================================================= furnace =====


def cand_furnace(field_col: Color | None, k: int) -> tuple[Image.Image, Image.Image]:
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    crop = load_crop("block/furnace_front_on.png", FURNACE_CROP_BOX)
    place_nearest(scene, crop, k, SIDE // 2, SIDE // 2)
    if field_col is None:
        return scene, scene
    return on_field(scene, ground(field_col)), scene


# ============================================================== hopper =====


# Rows 8-12 of item/hopper.png (getbbox-cropped) are the funnel narrowing
# from 6px down to a 2px spout tip (checked by printing the crop as ASCII).
# HOPPER_INGOT_OVERLAP pulls the ingot up into that narrowing neck instead of
# butting it against the hopper's widest point, which is what made the first
# fix (zero gap, no overlap) still measure thin: the hopper is 14 wide and 13
# tall and the ingot is 16 wide and 12 tall, so stacked with no overlap the
# pair is 16x25 - tall and narrow, so pushing the bbox target up mostly adds
# empty side padding rather than painted area (measured: bbox 85%, coverage
# only 28%, i.e. the coordinator's "外接矩形が大きいだけで中身が痩せている").
# Overlapping by 6 rows brings the pair to 16x19, close enough to square that
# a bbox target actually buys back area instead of padding.
HOPPER_INGOT_OVERLAP = 6


def cand_hopper(field_col: Color | None, k: int) -> tuple[Image.Image, Image.Image]:
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    hopper = load_crop(HOPPER_SRC)
    ingot = load_crop(INGOT_SRC, (0, 2, 16, 14))
    total_h = (hopper.height + ingot.height - HOPPER_INGOT_OVERLAP) * k
    top = SIDE // 2 - total_h // 2
    hx0, hy0, hw, hh = place_nearest(
        scene, hopper, k, SIDE // 2, top + hopper.height * k // 2
    )
    ingot_cy = hy0 + hh - HOPPER_INGOT_OVERLAP * k + ingot.height * k // 2
    place_nearest(scene, ingot, k, SIDE // 2, ingot_cy)
    if field_col is None:
        return scene, scene
    return on_field(scene, ground(field_col)), scene


# ================================================================ sheets ===

CLAIMS = {
    "A_furnace_rust": "the furnace kept cooking the entire time you were gone",
    "B_furnace_charcoal": "the furnace kept cooking the entire time you were gone",
    "C_furnace_clear": "the furnace kept cooking the entire time you were gone",
    "D_hopper_copper": "the hopper never stopped feeding what was under it",
    "E_hopper_patina": "the hopper never stopped feeding what was under it",
    "F_hopper_clear": "the hopper never stopped feeding what was under it",
}

FIELDS = {
    "A_furnace_rust": FIELD_RUST,
    "B_furnace_charcoal": FIELD_CHARCOAL,
    "C_furnace_clear": None,
    "D_hopper_copper": FIELD_COPPER,
    "E_hopper_patina": FIELD_PATINA,
    "F_hopper_clear": None,
}


def build(name: str) -> tuple[Image.Image, Image.Image]:
    # k is the integer NEAREST factor (LOGO_PLAYBOOK: "pixel-art elements
    # placed at an integer multiple of NEAREST before the whole frame is
    # reduced"). Solved from k = target_bbox_share * SIDE / source_span,
    # where source_span is the crop's own dominant dimension in source
    # pixels: furnace 14 (square crop); hopper+ingot overlapped by
    # HOPPER_INGOT_OVERLAP to 16x19, so 19 governs. Overlapping (rather than
    # just butting the two together) is what actually fixed the coordinator's
    # "D/E だけ27%は薄すぎる" finding - a tall, narrow pair could not be
    # pushed to a high bbox without mostly buying side padding; a
    # near-square pair can.
    if name.startswith("A") or name.startswith("B"):
        return cand_furnace(FIELDS[name], k=124)  # bbox 84.8%
    if name == "C_furnace_clear":
        return cand_furnace(None, k=135)  # bbox 92.3%
    if name in ("D_hopper_copper", "E_hopper_patina"):
        return cand_hopper(FIELDS[name], k=92)  # bbox ~85%
    return cand_hopper(None, k=99)  # bbox ~92%


CANDIDATES = {name: (lambda n=name: build(n)) for name in CLAIMS}


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
    """Subject bbox share of the side, and coverage - measured on the raw
    subject alpha (the un-composited scene, transparent everywhere but the
    drawn shapes), so the field's own rounded-corner antialiasing halo never
    gets counted as subject. That halo is what made an earlier pass of this
    script read every flat-field candidate as ~100% bbox regardless of the
    scale actually drawn."""
    alpha = scene.convert("RGBA").split()[3]
    w, h = alpha.size
    a = alpha.load()
    xs, ys, n = [], [], 0
    for yy in range(h):
        for xx in range(w):
            if a[xx, yy] > 20:
                xs.append(xx)
                ys.append(yy)
                n += 1
    if not xs:
        return (0.0, 0.0)
    bbox = max(max(xs) - min(xs), max(ys) - min(ys)) / w
    return (bbox, n / (w * h))


def contact_sheet(
    icons: dict[str, Image.Image], metrics_map: dict[str, tuple[float, float]]
) -> Image.Image:
    TILE, PAD, LABEL = 340, 22, 58
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
        sd.text(
            (cx + 2, cy - LABEL + 2),
            f"{name}   bbox {bbox:.0%}   coverage {cov:.0%}",
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
        for k, ln in enumerate(lines[:2]):
            sd.text(
                (cx + 2, cy - LABEL + 24 + k * 16),
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
    d.rectangle([0, ROW + GUT, w, img.height], fill=(30, 31, 34))
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
            d.text(
                (x, y0 + 64 + 48 + 16),
                name[:1] + name[2:].split("_")[0],
                fill=ink,
                font=f,
            )
        del band
    return img


def main() -> None:
    CAND.mkdir(parents=True, exist_ok=True)
    icons: dict[str, Image.Image] = {}
    metrics_map: dict[str, tuple[float, float]] = {}
    for name, fn in CANDIDATES.items():
        raw_icon, raw_scene = fn()
        icon = finish(raw_icon)
        for size in (512, 256, 128, 64, 48):
            out = icon if size == 512 else icon.resize((size, size), Image.LANCZOS)
            out.save(CAND / f"meanwhile_{name}_{size}.png")
        icons[name] = icon
        bbox, cov = metrics(raw_scene)
        metrics_map[name] = (bbox, cov)
        print(f"{name:20s} bbox {bbox:5.1%}  coverage {cov:5.1%}   {CLAIMS[name]}")
    contact_sheet(icons, metrics_map).save(CAND / "_contact_sheet.png")
    size_check(icons).save(CAND / "_small_size_check.png")
    print("\nsaved", CAND / "_contact_sheet.png")
    print("saved", CAND / "_small_size_check.png")


if __name__ == "__main__":
    main()
