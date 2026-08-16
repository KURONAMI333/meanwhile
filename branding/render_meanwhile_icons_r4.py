# -*- coding: utf-8 -*-
"""Meanwhile store icon - round 4 candidates.

kura's verdict on round 3 (six candidates, all either a lit furnace or a
hopper mid-feed, both built from real vanilla pixel crops):

  かまどはだめだな　ホッパーはロゴとしてはまあまあ良いけど、機能とは違うか？
  つまりどっちもだめ

The pixel-crop technique was not the problem - the coordinator's review
confirmed that and it is kept unchanged below. What was rejected is the
*family*: every round 3 candidate was "one representative machine, drawn
big" - Subject-Max's rule 1 read the straightforward way. That register
answers "what does this mod touch", and for a mod with no block or item of
its own, the honest answer is "any machine", so a furnace becomes a furnace
mod and a hopper becomes a hopper mod. Neither says the thing this mod
actually does: **time passed, and the work kept going without anyone there
to watch it.** A static portrait of a machine cannot carry a claim about
elapsed time, no matter how bold or how correctly cropped.

So round 4 does not put a machine portrait in any candidate. Three
sub-families instead, each locating "the work happened" somewhere other than
"here is a machine":

  family 1  progress as glyph      the furnace GUI's own smelting arrow,
                                    isolated and placed on a flat saturated
                                    field - LOGO_PLAYBOOK's other cited
                                    winning register (sodium / lithium / jei
                                    / iris: flat colour field + white glyph),
                                    not the single-bold-object register round
                                    3 used.
  family 2  before -> after         what you left is not what you get back.
                                    two real item sprites, raw and result,
                                    juxtaposed - the claim is carried by the
                                    *pair*, and no machine is drawn at all.
  family 3  elapsed time + work     one attempt at fusing a small, genuine
                                    time-telling element with the work glyph.
                                    Flagged as the weakest of the round in
                                    the report below, per the coordinator's
                                    explicit permission to drop it rather
                                    than force a fourth family that reads as
                                    a sleep/time-skip mod.

None of the three is unloaded-activity's own solution (a day/night split
field with logo lettering baked in) - that was named explicitly as a
temporal device to solve the same problem *differently*, not to copy.

What is kept from round 3, unchanged: every subject pixel below is read out
of the real vanilla 1.21.1 PNG and placed at an integer NEAREST factor
before the SS=4 -> 512 LANCZOS reduction; no vector redraw of any object; no
rounded corners on anything that would be rectilinear in the game; colour
fields are still sampled from the subject's own material, not picked to
dodge another icon's colour; 48px legibility is checked on both a light and
a dark platform background.

Sources, exact rectangles, and the reasoning for each are documented beside
the constant that names them, in the "texture crops" section below.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- canvas ----

SS = 4
OUT = 512
SIDE = OUT * SS

BRANDING = Path(__file__).resolve().parent
CAND = BRANDING / "icon-candidates-r4"
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
WHITE = hexc("#ffffff")

# ------------------------------------------------------------- palettes ----
# Fields only - nothing here is a hand-picked subject colour, every subject
# below is real texture pixels. Each field is still mixed from a vanilla
# sample (LOGO_PLAYBOOK, "colour is taken from the subject").

FIRE_MID = hexc("#dfa21b")  # campfire_fire.png, mid tone - family 1's bright field
FIRE_LOW = hexc("#b13f00")  # campfire_fire.png, low tone
COAL = hexc("#151515")  # coal_block.png
PATINA = hexc("#59b292")  # oxidized_copper.png - "sat untouched", kept from round 3

FIELD_AMBER = mix(FIRE_MID, WHITE, 0.06)  # family 1a: bright, saturated - the
# playbook's own cited register (sodium/lithium/jei/iris) sits at S45-76%
# V78-100%; straight FIRE_MID reads a little dull next to that band, so it is
# lifted 6% toward white rather than left as-sampled.
FIELD_RUST = mix(FIRE_LOW, BLACK, 0.30)  # family 1b: the same fire, banked down
FIELD_CHARCOAL = mix(COAL, FIRE_LOW, 0.22)  # family 2's iron pair: soot with a
# coal still in it, dark enough to hold both a pale ingot and a tan raw lump
FIELD_PATINA = mix(PATINA, BLACK, 0.08)  # family 3: still "sat untouched"

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


# --- the smelting arrow, gui/container/furnace.png -------------------------
# The furnace GUI's ingredient-slot -> arrow -> output-slot row is vanilla's
# own way of drawing "conversion in progress" - it is what every Minecraft
# player who has ever smelted anything has looked at while waiting. The
# arrow alone (not the slots) is the glyph: found by cropping the region
# around it, then keeping only the pixels matching its own fill colour
# (139,139,139) as opposed to the panel's background grey (198,198,198),
# confirmed to isolate cleanly with no stray same-colour pixels inside this
# exact box (checked visually before use, gate0/inspect/arrow_tight.png).
ARROW_BOX = (80, 35, 102, 50)  # gui/container/furnace.png; 22x15
ARROW_FILL = (139, 139, 139)


def load_arrow(recolor: Color) -> Image.Image:
    crop = load_crop("gui/container/furnace.png", ARROW_BOX)
    w, h = crop.size
    px = crop.load()
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y in range(h):
        for x in range(w):
            if px[x, y][:3] == ARROW_FILL:
                out.putpixel((x, y), recolor)
    return out


# --- before/after item pairs ------------------------------------------------
# item/raw_iron.png -> item/iron_ingot.png: the ore this mod's own furnace
# work turns into a bar while nobody watches. item/beef.png ->
# item/cooked_beef.png: the more universally legible pair, since the colour
# swap (red -> brown) reads as "cooked" without needing the viewer to know
# what raw iron looks like.
RAW_IRON_BOX = (0, 1, 16, 15)  # 16x14
INGOT_BOX = (0, 2, 16, 14)  # 16x12
BEEF_BOX = (2, 3, 15, 15)  # 13x12, shared crop for beef.png and cooked_beef.png

# --- the clock, item/clock_00.png -------------------------------------------
# Vanilla's clock item - not an hourglass (unloaded-activity's direct
# competitor already owns that shape) and not a bed/moon (which is what
# actually reads as "sleep mod"). Frame 00 was picked over other frames
# after checking several: the dial's sun/moon wedge indicator does not read
# as clearly as an analogue clock face at small size even in isolation
# (gate0/inspect/clock_00.png, gate0/inspect/clock_16.png) - flagged in the
# report rather than hidden.
CLOCK_BOX = None  # item/clock_00.png's own alpha bbox


# ============================================================= family 1 ===


def cand_arrow(field_col: Color, k: int) -> tuple[Image.Image, Image.Image]:
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    arrow = load_arrow(WHITE)
    place_nearest(scene, arrow, k, SIDE // 2, SIDE // 2)
    return on_field(scene, ground(field_col)), scene


# ============================================================= family 2 ===


def cand_pair_horizontal(
    before_src: str,
    before_box,
    after_src: str,
    after_box,
    field_col: Color | None,
    k_item: int,
    with_arrow: bool,
    arrow_col: Color,
) -> tuple[Image.Image, Image.Image]:
    """Two items side by side, optionally with the arrow between them."""
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    before = load_crop(before_src, before_box)
    after = load_crop(after_src, after_box)
    gap = int(SIDE * 0.08)
    bw = before.width * k_item
    aw = after.width * k_item
    total_w = bw + gap + aw
    left_cx = SIDE // 2 - total_w // 2 + bw // 2
    right_cx = SIDE // 2 + total_w // 2 - aw // 2
    place_nearest(scene, before, k_item, left_cx, SIDE // 2)
    place_nearest(scene, after, k_item, right_cx, SIDE // 2)
    if with_arrow:
        arrow = load_arrow(arrow_col)
        k_arrow = max(1, (gap * 3 // 4) // arrow.width)
        place_nearest(scene, arrow, k_arrow, SIDE // 2, SIDE // 2)
    if field_col is None:
        return scene, scene
    return on_field(scene, ground(field_col)), scene


def cand_pair_vertical(
    top_src: str,
    top_box,
    bottom_src: str,
    bottom_box,
    field_col: Color | None,
    k_item: int,
) -> tuple[Image.Image, Image.Image]:
    """Two items stacked, no arrow - the colour shift alone carries "cooked"."""
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    top = load_crop(top_src, top_box)
    bottom = load_crop(bottom_src, bottom_box)
    gap = int(SIDE * 0.06)
    th = top.height * k_item
    bh = bottom.height * k_item
    total_h = th + gap + bh
    top_cy = SIDE // 2 - total_h // 2 + th // 2
    bot_cy = SIDE // 2 + total_h // 2 - bh // 2
    place_nearest(scene, top, k_item, SIDE // 2, top_cy)
    place_nearest(scene, bottom, k_item, SIDE // 2, bot_cy)
    if field_col is None:
        return scene, scene
    return on_field(scene, ground(field_col)), scene


def cand_pair_diagonal(
    before_src: str,
    before_box,
    after_src: str,
    after_box,
    field_col: Color | None,
    k_item: int,
    arrow_col: Color,
) -> tuple[Image.Image, Image.Image]:
    """Before at lower-left, after at upper-right, the arrow between on the
    same diagonal - the arrow itself stays axis-aligned (rotating a NEAREST
    pixel crop would blur it back into the "drawn from memory" failure)."""
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    before = load_crop(before_src, before_box)
    after = load_crop(after_src, after_box)
    span = int(SIDE * 0.24)
    place_nearest(scene, before, k_item, SIDE // 2 - span, SIDE // 2 + span)
    place_nearest(scene, after, k_item, SIDE // 2 + span, SIDE // 2 - span)
    arrow = load_arrow(arrow_col)
    k_arrow = max(1, int(SIDE * 0.16) // arrow.width)
    place_nearest(scene, arrow, k_arrow, SIDE // 2, SIDE // 2)
    if field_col is None:
        return scene, scene
    return on_field(scene, ground(field_col)), scene


# ============================================================= family 3 ===


def cand_time_and_work(
    field_col: Color, k_arrow: int, k_ingot: int, k_clock: int
) -> tuple[Image.Image, Image.Image]:
    """Arrow + result carry the primary read (same as family 1/2); the clock
    is a small secondary badge, not the hero - if the clock alone is what
    has to carry "time passed", it is too small and too ambiguous at 48px
    to do it (see CLOCK_BOX note above), so the arrow+ingot pair is what
    actually has to survive the reduction on its own."""
    scene = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    arrow = load_arrow(WHITE)
    ingot = load_crop("item/iron_ingot.png", INGOT_BOX)
    place_nearest(scene, arrow, k_arrow, int(SIDE * 0.30), SIDE // 2)
    place_nearest(scene, ingot, k_ingot, int(SIDE * 0.72), SIDE // 2)
    clock = load_crop("item/clock_00.png", CLOCK_BOX)
    place_nearest(scene, clock, k_clock, int(SIDE * 0.16), int(SIDE * 0.78))
    return on_field(scene, ground(field_col)), scene


# ================================================================ build ===

CLAIMS = {
    "A_arrow_amber": "the same work vanilla shows you smelting anything - it kept happening",
    "B_arrow_rust": "the same work vanilla shows you smelting anything - it kept happening",
    "C_pair_iron_h": "what you left as ore, you get back as ingots",
    "D_pair_beef_v": "what you left raw, you get back cooked",
    "E_pair_iron_diag": "what you left as ore, you get back as ingots",
    "F_time_work": "time passed, and the work kept pace with it",
}

FAMILY = {
    "A_arrow_amber": "family 1: progress as glyph",
    "B_arrow_rust": "family 1: progress as glyph",
    "C_pair_iron_h": "family 2: before -> after",
    "D_pair_beef_v": "family 2: before -> after",
    "E_pair_iron_diag": "family 2: before -> after",
    "F_time_work": "family 3: elapsed time + work (weakest - see report)",
}

FIELDS: dict[str, Color | None] = {
    "A_arrow_amber": FIELD_AMBER,
    "B_arrow_rust": FIELD_RUST,
    "C_pair_iron_h": FIELD_CHARCOAL,
    "D_pair_beef_v": None,
    "E_pair_iron_diag": None,
    "F_time_work": FIELD_PATINA,
}


def build(name: str) -> tuple[Image.Image, Image.Image]:
    if name == "A_arrow_amber":
        return cand_arrow(FIELD_AMBER, k=84)
    if name == "B_arrow_rust":
        return cand_arrow(FIELD_RUST, k=84)
    if name == "C_pair_iron_h":
        return cand_pair_horizontal(
            "item/raw_iron.png",
            RAW_IRON_BOX,
            "item/iron_ingot.png",
            INGOT_BOX,
            FIELD_CHARCOAL,
            k_item=46,
            with_arrow=True,
            arrow_col=WHITE,
        )
    if name == "D_pair_beef_v":
        return cand_pair_vertical(
            "item/beef.png",
            BEEF_BOX,
            "item/cooked_beef.png",
            BEEF_BOX,
            None,
            k_item=66,
        )
    if name == "E_pair_iron_diag":
        return cand_pair_diagonal(
            "item/raw_iron.png",
            RAW_IRON_BOX,
            "item/iron_ingot.png",
            INGOT_BOX,
            None,
            k_item=40,
            arrow_col=FIELD_AMBER,
        )
    return cand_time_and_work(FIELD_PATINA, k_arrow=28, k_ingot=48, k_clock=34)


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
    subject alpha (pre-field scene), same method as round 3's corrected
    metrics (the field's own rounded-corner antialiasing halo must not be
    counted as subject)."""
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
    TILE, PAD, LABEL = 340, 22, 74
    cols = 3
    rows_n = (len(icons) + cols - 1) // cols
    sheet = Image.new(
        "RGB",
        (PAD + cols * (TILE + PAD), PAD + rows_n * (TILE + LABEL + PAD)),
        (52, 54, 60),
    )
    sd = ImageDraw.Draw(sheet)
    f_title, f_fam, f_claim = label_font(15), label_font(12), label_font(13)
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
            (cx + 2, cy - LABEL + 2), FAMILY[name], fill=(150, 200, 255), font=f_fam
        )
        sd.text(
            (cx + 2, cy - LABEL + 18),
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
                (cx + 2, cy - LABEL + 40 + k * 16),
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
    f = label_font(10)
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
            d.text((x, y0 + 64 + 48 + 16), name[:1] + name[2:6], fill=ink, font=f)
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
        print(f"{name:20s} [{FAMILY[name]}]")
        print(f"    bbox {bbox:5.1%}  coverage {cov:5.1%}   {CLAIMS[name]}")
    contact_sheet(icons, metrics_map).save(CAND / "_contact_sheet_r4.png")
    size_check(icons).save(CAND / "_small_size_check_r4.png")
    print("\nsaved", CAND / "_contact_sheet_r4.png")
    print("saved", CAND / "_small_size_check_r4.png")


if __name__ == "__main__":
    main()
