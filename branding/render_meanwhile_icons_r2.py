# -*- coding: utf-8 -*-
"""Meanwhile store identity - round 2 candidates (icon + wordmark + banner).

Round 1 (four flat abstract marks: tally, steps, chunk grid, furnace mouth) was
rejected in full. The reasoning that produced it was: LOGO_PLAYBOOK's Subject-Max
rule asks for pixel art of "the mod's representative block or item", this mod
registers neither, therefore there is no subject, therefore the mark must be
abstract. That last step is wrong. Three finished icons in this family draw a
*scene*, or a claim, built around a subject the mod never registers:

  mod-066-map-art-maker/branding/map_art_maker_icon_256.png - an easel holding a
      pixel landscape, wooden legs, warm tan ground.
  mod-030-sky-world/branding/sky_world_icon_256.png - floating islands, a tree
      and clouds on flat sky blue; there is no "sky world block" to draw.
  mod-062-welcome-board/branding/welcome_board_icon_256.png - the function
      spelled out as two glyphs burned into a flat amber field.

So the register the family actually uses is: recognisable subject, several
elements, depth, saturated flat ground, high occupancy, rounded square. This
round works in that register.

Meanwhile's subject is available without registering anything: **a base that
kept working while nobody was there.** The mod settles what your machines missed
while their chunk was unloaded, so absence and return are the thing to draw.

Step 0 (shelf measurement) is not re-run. It was done for round 1 (26 icons,
three cohorts, shelf bbox median 77%) and it found three registers on the shelf:
one bold silhouette on a plain field, baked text, and pixel-art objects. It found
no scenes. That conflict is named rather than hidden: the illustrative scene is a
KURONAMI-family convention, not a shelf convention, and kura rejecting four
shelf-register marks is what breaks the tie.

Four candidates, deliberately in four different *registers* rather than four
moments of one story - two night exteriors would be one candidate, not two:

  A  room        isometric cutaway, machines mid-job, empty floor, ochre field.
                 Claim: your machines finished the job with nobody in the room.
  B  distance    landscape; the far land you are not standing in, still smoking.
                 Claim: the part of the world you left keeps running without you.
  C  house       one drawn object on a flat night field: the base, lit, shut.
                 Claim: the lights are on, nobody is home, the work goes on.
  D  letters     the name burned in, split where the word splits: MEAN / WHILE.
                 Claim: elsewhere, at the same time - which is what the mod does.

None of them is a clock, an hourglass or a stopwatch; the one mod on the shelf
doing this job already owns the hourglass.

Colour comes from the subject (LOGO_PLAYBOOK, "colour is taken from the
subject"). Every material constant below is sampled from the vanilla 1.21.1
texture named beside it, read out of the local asset cache rather than recalled;
no colour here was chosen to avoid clashing with another mod's icon. Where a
colour has no vanilla texture to come from (night sky, dusk band) the reasoning
is stated at the constant.

Geometry: SS=4 (2048px composite -> 512 LANCZOS). Every rectilinear edge is
placed on a 128-unit design grid whose unit is 16px at SS and 4px at 512, so
straight edges stay hard through the reduction and only the deliberate diagonals
(roof, isometric rhombi) are anti-aliased.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- canvas ----

SS = 4  # supersample factor
OUT = 512  # master export size
GRID = 128  # design grid; 1 unit = 4px at 512, 16px at SS
U = (OUT * SS) // GRID  # px per design unit at SS
SIDE = OUT * SS

BRANDING = Path(__file__).resolve().parent
CAND = BRANDING / "icon-candidates-r2"

Color = tuple[int, int, int, int]


def hexc(h: str, a: int = 255) -> Color:
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1: Color, c2: Color, t: float) -> Color:
    return tuple(round(a + (b - a) * t) for a, b in zip(c1, c2))  # type: ignore[return-value]


def shade(c: Color, d: int) -> Color:
    r, g, b, a = c
    return (
        max(0, min(255, r + d)),
        max(0, min(255, g + d)),
        max(0, min(255, b + d)),
        a,
    )


# ------------------------------------------------------------- palettes ----
# Sampled from tool_data/cache/vanilla_textures/block/<name>.png (MC 1.21.1),
# read out of the cache rather than recalled.

STONE_BRICK = hexc("#7f7f7f")  # stone_bricks, dominant
STONE_BRICK_D = hexc("#5a595a")  # stone_bricks, darkest
STONE_BRICK_L = hexc("#9c999c")  # stone_bricks, lightest
COBBLE = hexc("#888788")  # cobblestone, dominant
COBBLE_D = hexc("#616161")  # cobblestone, second
FURNACE = hexc("#686767")  # furnace_side, dominant band
FURNACE_L = hexc("#858585")  # furnace_side, light band
FURNACE_MOUTH = hexc("#111111")  # furnace_front_on, darkest
FIRE_HOT = hexc("#efcd56")  # campfire_fire, dominant
FIRE_MID = hexc("#c96c03")  # campfire_fire, second
BARREL = hexc("#8b673c")  # barrel_side, dominant
BARREL_D = hexc("#282220")  # barrel_side, hoops
BARREL_L = hexc("#997140")  # barrel_side, light stave
OAK = hexc("#b8945f")  # oak_planks, dominant
OAK_D = hexc("#67502c")  # oak_planks, darkest
SPRUCE = hexc("#826139")  # spruce_planks, dominant
SPRUCE_D = hexc("#553a1f")  # spruce_planks, darkest
IRON = hexc("#d8d8d8")  # iron_block, light face
HOPPER = hexc("#3f3e42")  # hopper_outside, dominant
SAND = hexc("#dacfa3")  # sand, dominant
DIRT = hexc("#79553a")  # dirt, dominant
SPRUCE_LEAF = hexc("#3f5b34")  # spruce_leaves x its own foliage tint

# The field colour for A: sand's dominant tone taken two steps down, so a grey
# room and an orange fire both separate from it at 48px.
OCHRE = hexc("#c2a768")

# No vanilla texture holds "the sky at night", so these are stated rather than
# sampled. The night field is the blue the sky settles to once the sun is down,
# kept saturated so it reads as a colour field and not as black; the dusk band is
# the warm horizon that shows in the same frame at that hour.
NIGHT = hexc("#1d3452")
NIGHT_D = hexc("#0f2033")
DUSK_HI = hexc("#223d63")
DUSK_MID = hexc("#4a6a8c")
DUSK_WARM = hexc("#c98a55")
DUSK_HAZE = hexc("#8ca6bd")
WINDOW = hexc("#f2c26a")  # a lit window seen from outside at night
SMOKE = hexc("#d3d9df")
INK = hexc("#241f1a")


# ------------------------------------------------------------- drawing -----


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def rect(
    d: ImageDraw.ImageDraw, x0: float, y0: float, x1: float, y1: float, col: Color
) -> None:
    """Rectangle in design units, half-open, aligned to the unit grid."""
    d.rectangle([x0 * U, y0 * U, x1 * U - 1, y1 * U - 1], fill=col)


def poly(d: ImageDraw.ImageDraw, pts, col: Color) -> None:
    d.polygon([(x * U, y * U) for x, y in pts], fill=col)


def ground(col: Color, radius_pct: float = 0.20) -> Image.Image:
    """Flat saturated field, rounded square (LOGO_PLAYBOOK: r = 18-22% of side)."""
    img = Image.new("RGBA", (SIDE, SIDE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(
        [0, 0, SIDE - 1, SIDE - 1], radius=int(SIDE * radius_pct), fill=col
    )
    return img


def on_field(scene: Image.Image, field: Image.Image) -> Image.Image:
    """Keep only what falls inside the rounded field, then sit it on the field."""
    out = field.copy()
    empty = Image.new("RGBA", scene.size, (0, 0, 0, 0))
    out.alpha_composite(Image.composite(scene, empty, field.split()[3]))
    return out


def finish(img: Image.Image) -> Image.Image:
    return img.resize((OUT, OUT), Image.LANCZOS)


# ------------------------------------------------------- isometric block ---
# 2:1 isometric. A block's top face is a rhombus TW x TW/2 and its two visible
# sides are TH tall. Faces are flat tones off one material colour (top lightest,
# +y face mid, +x face darkest) - the tone split of a vanilla block render, not
# a gradient. Draw order is by (gx + gy, gz): far and low first.

TW, TH = 32, 16
LEFT, RIGHT, TOP = "left", "right", "top"  # left = +y face, right = +x face


def iso_center(
    gx: float, gy: float, gz: float, ox: float, oy: float
) -> tuple[float, float]:
    return (ox + (gx - gy) * (TW / 2), oy + (gx + gy) * (TW / 4) - gz * TH)


def face_quad(gx: float, gy: float, gz: float, ox: float, oy: float, which: str):
    """Corner list of one face, in design units."""
    cx, cy = iso_center(gx, gy, gz, ox, oy)
    hw, hh = TW / 2, TW / 4
    if which == TOP:
        return [(cx, cy - hh), (cx + hw, cy), (cx, cy + hh), (cx - hw, cy)]
    if which == LEFT:
        return [(cx - hw, cy), (cx, cy + hh), (cx, cy + hh + TH), (cx - hw, cy + TH)]
    return [(cx + hw, cy), (cx, cy + hh), (cx, cy + hh + TH), (cx + hw, cy + TH)]


def face_point(quad, u: float, v: float) -> tuple[float, float]:
    """Point inside a side face: u runs along its top edge, v runs down it."""
    a, b, _, dd = quad
    return (
        a[0] + (b[0] - a[0]) * u + (dd[0] - a[0]) * v,
        a[1] + (b[1] - a[1]) * u + (dd[1] - a[1]) * v,
    )


def face_rect(d, quad, u0: float, v0: float, u1: float, v1: float, col: Color) -> None:
    poly(
        d,
        [
            face_point(quad, u0, v0),
            face_point(quad, u1, v0),
            face_point(quad, u1, v1),
            face_point(quad, u0, v1),
        ],
        col,
    )


def block(
    d,
    gx,
    gy,
    gz,
    ox,
    oy,
    base: Color,
    faces=(TOP, LEFT, RIGHT),
    tones: dict[str, Color] | None = None,
) -> None:
    tones = tones or {}
    order = [f for f in (LEFT, RIGHT, TOP) if f in faces]
    default = {TOP: shade(base, 20), LEFT: shade(base, -18), RIGHT: shade(base, -42)}
    for f in order:
        poly(d, face_quad(gx, gy, gz, ox, oy, f), tones.get(f, default[f]))


# ------------------------------------------------------------ pixel type ---
# 5x7 caps drawn as bitmaps and placed on the unit grid at an integer scale, so
# the letters stay hard-edged through the reduction.

# M and W keep five columns because they need the diagonal; every other cap is
# condensed to four. At the scale legibility at 48px demands, a uniform 5-wide
# face leaves the word 5% of margin, which reads as cramped; this buys 12%.
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


# ============================================================ candidate A ===


def draw_room(d, ox: float, oy: float) -> None:
    """The diorama itself, so the banner can reuse it at another origin."""
    floor = 3  # the middle of the floor is meant to stay empty
    jobs: list[tuple[tuple[float, float], object]] = []

    def add(gx, gy, gz, fn) -> None:
        jobs.append(((gx + gy, gz), fn))

    # No walls. A room drawn in isometric reads as a grey crate at 48px; the
    # slab on its own reads as one chunk of your base, lifted out of the world -
    # which is the unit the mod actually settles.
    # floor slab: chequered stone brick, with its two front edges cut solid
    for gx in range(floor):
        for gy in range(floor):
            tone = STONE_BRICK_L if (gx + gy) % 2 == 0 else STONE_BRICK
            add(
                gx,
                gy,
                0,
                lambda gx=gx, gy=gy, t=tone: block(
                    d, gx, gy, 0, ox, oy, STONE_BRICK, faces=(TOP,), tones={TOP: t}
                ),
            )
    for gx in range(floor):
        add(
            gx,
            floor - 1,
            0.5,
            lambda gx=gx: block(
                d,
                gx,
                floor - 1,
                0,
                ox,
                oy,
                STONE_BRICK,
                faces=(LEFT,),
                tones={LEFT: STONE_BRICK_D},
            ),
        )
    for gy in range(floor):
        add(
            floor - 1,
            gy,
            0.5,
            lambda gy=gy: block(
                d,
                floor - 1,
                gy,
                0,
                ox,
                oy,
                STONE_BRICK,
                faces=(RIGHT,),
                tones={RIGHT: shade(STONE_BRICK_D, -12)},
            ),
        )

    for gx in range(floor):
        for gz in (0, -1):
            add(
                gx,
                floor - 1,
                0.5 + gz * 0.01,
                lambda gx=gx, gz=gz: block(
                    d,
                    gx,
                    floor - 1,
                    gz,
                    ox,
                    oy,
                    STONE_BRICK,
                    faces=(LEFT,),
                    tones={
                        LEFT: STONE_BRICK_D if gz == 0 else shade(STONE_BRICK_D, -16)
                    },
                ),
            )
    for gy in range(floor):
        for gz in (0, -1):
            add(
                floor - 1,
                gy,
                0.5 + gz * 0.01,
                lambda gy=gy, gz=gz: block(
                    d,
                    floor - 1,
                    gy,
                    gz,
                    ox,
                    oy,
                    STONE_BRICK,
                    faces=(RIGHT,),
                    tones={RIGHT: shade(STONE_BRICK_D, -12 if gz == 0 else -28)},
                ),
            )
    # a course of dirt under the stone, the way a chunk section reads in section
    for gx in range(floor):
        add(
            gx,
            floor - 1,
            0.4,
            lambda gx=gx: block(
                d, gx, floor - 1, -2, ox, oy, DIRT, faces=(LEFT,), tones={LEFT: DIRT}
            ),
        )
    for gy in range(floor):
        add(
            floor - 1,
            gy,
            0.4,
            lambda gy=gy: block(
                d,
                floor - 1,
                gy,
                -2,
                ox,
                oy,
                DIRT,
                faces=(RIGHT,),
                tones={RIGHT: shade(DIRT, -22)},
            ),
        )

    # the floor tile the open firebox faces, carrying its light as a flat tone
    add(
        0,
        1,
        0.6,
        lambda: block(
            d,
            0,
            1,
            0,
            ox,
            oy,
            STONE_BRICK,
            faces=(TOP,),
            tones={TOP: mix(STONE_BRICK_L, FIRE_MID, 0.34)},
        ),
    )

    # furnace, back corner, its lit mouth facing into the room
    def furnace() -> None:
        block(
            d,
            0,
            0,
            1,
            ox,
            oy,
            FURNACE,
            tones={
                TOP: FURNACE_L,
                LEFT: shade(FURNACE, -10),
                RIGHT: shade(FURNACE, -38),
            },
        )
        q = face_quad(0, 0, 1, ox, oy, LEFT)
        face_rect(d, q, 0.14, 0.10, 0.86, 0.40, shade(FURNACE, -6))  # cold upper slot
        face_rect(d, q, 0.20, 0.16, 0.80, 0.34, FURNACE_MOUTH)
        face_rect(d, q, 0.10, 0.46, 0.90, 0.94, FURNACE_MOUTH)  # the fire box
        face_rect(d, q, 0.16, 0.54, 0.84, 0.90, FIRE_MID)
        face_rect(d, q, 0.26, 0.60, 0.74, 0.84, FIRE_HOT)

    add(0, 0, 1, furnace)

    # barrel, the other back corner, its lid open because it is being filled
    def barrel() -> None:
        block(
            d,
            2,
            0,
            1,
            ox,
            oy,
            BARREL,
            tones={TOP: BARREL_L, LEFT: BARREL, RIGHT: shade(BARREL, -26)},
        )
        top = face_quad(2, 0, 1, ox, oy, TOP)
        cx, cy = iso_center(2, 0, 1, ox, oy)
        poly(
            d,
            [
                (cx, cy - TW / 8),
                (cx + TW / 4, cy),
                (cx, cy + TW / 8),
                (cx - TW / 4, cy),
            ],
            BARREL_D,
        )
        for q, base in (
            (face_quad(2, 0, 1, ox, oy, LEFT), BARREL_L),
            (face_quad(2, 0, 1, ox, oy, RIGHT), shade(BARREL, -12)),
        ):
            face_rect(d, q, 0.0, 0.16, 1.0, 0.30, BARREL_D)
            face_rect(d, q, 0.0, 0.70, 1.0, 0.84, BARREL_D)
            face_rect(d, q, 0.0, 0.42, 1.0, 0.56, base)
        del top

    add(2, 0, 1, barrel)

    # a hopper still feeding, front corner
    def hopper() -> None:
        block(
            d,
            0,
            2,
            1,
            ox,
            oy,
            HOPPER,
            tones={TOP: shade(HOPPER, 34), LEFT: shade(HOPPER, 8), RIGHT: HOPPER},
        )
        for which, dark in ((LEFT, 10), (RIGHT, -8)):
            q = face_quad(0, 2, 1, ox, oy, which)
            face_rect(d, q, 0.0, 0.0, 1.0, 0.30, shade(HOPPER, 22 + dark))
            face_rect(d, q, 0.26, 0.52, 0.74, 1.0, shade(HOPPER, -14 + dark))

    add(0, 2, 1, hopper)

    # what got done while nobody was there: ingots stacked on the floor
    def ingots() -> None:
        cx, cy = iso_center(2, 2, 1, ox, oy)
        w, h = TW * 0.21, TW * 0.105
        for i in range(3):
            yy = cy + TH * 0.62 - i * (TH * 0.26)
            poly(
                d,
                [(cx, yy - h), (cx + w, yy), (cx, yy + h), (cx - w, yy)],
                shade(IRON, -30 + i * 14),
            )
        yy = cy + TH * 0.62 - 2 * (TH * 0.26)
        poly(d, [(cx, yy - h), (cx + w, yy), (cx, yy + h), (cx - w, yy)], IRON)

    add(2, 2, 1, ingots)

    for _, fn in sorted(jobs, key=lambda j: j[0]):
        fn()  # type: ignore[operator]


def cand_room() -> Image.Image:
    scene, d = canvas()
    draw_room(d, 64, 54)
    return on_field(scene, ground(OCHRE))


# ============================================================ candidate B ===


def draw_far_base(d, hx: float, hy: float, s: float = 1.0) -> None:
    """The base over there: walls, roof, one lit window, chimney and smoke."""
    body = shade(DUSK_HAZE, -72)
    dark = shade(DUSK_HAZE, -92)
    w, h = 26 * s, 13 * s
    rect(d, hx, hy, hx + w, hy + h, body)
    poly(d, [(hx - 3 * s, hy), (hx + w / 2, hy - 9 * s), (hx + w + 3 * s, hy)], dark)
    rect(d, hx + 5 * s, hy + 4 * s, hx + 12 * s, hy + 10 * s, WINDOW)
    rect(d, hx + 16 * s, hy + 4 * s, hx + 22 * s, hy + 10 * s, shade(dark, 8))
    # chimney, then smoke leaving it and widening as it climbs
    rect(d, hx + 17 * s, hy - 12 * s, hx + 23 * s, hy - 2 * s, dark)
    # The puffs are stacked with an overlap on purpose. Spaced apart they read as
    # a column of separate rectangles floating over the roof rather than as smoke.
    puffs = ((7, 0), (9, -2), (10, 2), (11, -3), (12, 1), (13, -2))
    yy = hy - 11 * s
    for i, (pw, drift) in enumerate(puffs):
        ph = pw * 0.75
        cx = hx + 20 * s + drift * s
        rect(
            d,
            cx - pw * s / 2,
            yy - ph * s,
            cx + pw * s / 2,
            yy,
            mix(SMOKE, DUSK_HI, 0.06 + i * 0.11),
        )
        yy -= (ph - 1.5) * s


def ridge(d, w: float, pts, y_bottom: float, col: Color) -> None:
    """A skyline given as (fraction across, y) points, so it fits any width."""
    poly(d, [(t * w, y) for t, y in pts] + [(w, y_bottom), (0, y_bottom)], col)


def draw_distance(d, w: float, base_x: float, base_s: float = 1.25) -> None:
    """The dusk landscape, drawn to any width; the vertical layout is fixed."""
    # sky in flat bands, warm at the horizon (no gradient, no glow)
    rect(d, 0, 0, w, 30, DUSK_HI)
    rect(d, 0, 30, w, 46, mix(DUSK_HI, DUSK_MID, 0.5))
    rect(d, 0, 46, w, 60, DUSK_MID)
    rect(d, 0, 60, w, 68, mix(DUSK_MID, DUSK_WARM, 0.5))
    rect(d, 0, 68, w, 74, DUSK_WARM)

    # the land over there, in haze, with the base standing on it
    ridge(d, w, [(0.00, 74), (0.13, 69), (0.28, 73), (0.42, 66), (0.59, 72),
                 (0.77, 67), (0.91, 73), (1.00, 70)], 84, DUSK_HAZE)
    draw_far_base(d, base_x, 58, base_s)

    # a band of dark land between there and here
    ridge(d, w, [(0.00, 82), (0.22, 78), (0.44, 83), (0.66, 77), (0.86, 82),
                 (1.00, 79)], 96, mix(DUSK_HAZE, NIGHT_D, 0.66))

    # here: the ground you are standing on, dark, with spruce on it
    ridge(d, w, [(0.00, 94), (0.19, 90), (0.39, 96), (0.61, 89), (0.83, 95),
                 (1.00, 91)], 128, NIGHT_D)
    for t, base_y, tiers in ((0.12, 116, 4), (0.81, 112, 5), (0.95, 122, 3)):
        tx = t * w
        trunk = shade(NIGHT_D, -6)
        rect(d, tx - 2, base_y - 6, tx + 2, base_y, trunk)
        for i in range(tiers):
            half = 11 - i * 2
            yy = base_y - 6 - i * 7
            rect(d, tx - half, yy - 8, tx + half, yy, trunk)


def cand_distance() -> Image.Image:
    scene, d = canvas()
    draw_distance(d, GRID, 46)
    return on_field(scene, ground(DUSK_HI))


# ============================================================ candidate C ===


def draw_house(d, x0: float, x1: float, wall_top: float, wall_bot: float) -> None:
    mid = (x0 + x1) / 2

    # It is night, so every material is the vanilla colour carried most of the
    # way down to the night field. Lit walls at their daylight value read as a
    # cheerful cottage and the windows stop being the brightest thing in frame.
    def dim(c: Color, t: float = 0.62) -> Color:
        return mix(c, NIGHT_D, t)

    roof, roof_d = dim(SPRUCE), dim(SPRUCE_D)
    wall, wall_d = dim(OAK, 0.55), dim(OAK_D)
    foot = dim(COBBLE_D, 0.55)

    # roof: two runs of stairs meeting at a ridge, the way it gets built
    steps, rise, run = 7, 5, 8
    for i in range(steps):
        y = wall_top - i * rise
        inset = i * run
        rect(
            d,
            x0 - 8 + inset,
            y - rise,
            x1 + 8 - inset,
            y,
            roof if i % 2 else shade(roof, 10),
        )
    rect(d, x0 - 8, wall_top, x1 + 8, wall_top + 4, roof_d)  # eaves

    # walls: oak over a cobble footing, with corner posts
    rect(d, x0, wall_top + 4, x1, wall_bot - 10, wall)
    rect(d, x0, wall_bot - 10, x1, wall_bot, foot)
    rect(d, x0, wall_top + 4, x0 + 7, wall_bot, wall_d)
    rect(d, x1 - 7, wall_top + 4, x1, wall_bot, wall_d)

    # two lit windows and a shut door
    for wx in (x0 + 13, x1 - 33):
        rect(d, wx, wall_top + 12, wx + 20, wall_bot - 22, wall_d)
        rect(d, wx + 3, wall_top + 15, wx + 17, wall_bot - 25, WINDOW)
        rect(d, wx + 9, wall_top + 15, wx + 11, wall_bot - 25, wall_d)
    dx = mid - 10
    rect(d, dx - 2, wall_bot - 32, dx + 22, wall_bot, roof_d)
    rect(d, dx, wall_bot - 30, dx + 20, wall_bot, roof)
    rect(d, dx + 14, wall_bot - 18, dx + 18, wall_bot - 14, shade(roof, 60))


def cand_house() -> Image.Image:
    scene, d = canvas()
    x0, x1 = 15, 113
    wall_top, wall_bot = 56, 106

    rect(d, 0, wall_bot, GRID, GRID, NIGHT_D)  # the ground it stands on
    draw_house(d, x0, x1, wall_top, wall_bot)

    # window light on the ground: two flat steps widening away from the wall,
    # which is what a lit window does to a dark yard. No gradient, no glow.
    for wx in (x0 + 16, x1 - 30):
        poly(
            d,
            [
                (wx, wall_bot),
                (wx + 14, wall_bot),
                (wx + 21, wall_bot + 8),
                (wx - 7, wall_bot + 8),
            ],
            mix(NIGHT_D, WINDOW, 0.60),
        )
        poly(
            d,
            [
                (wx - 7, wall_bot + 8),
                (wx + 21, wall_bot + 8),
                (wx + 28, wall_bot + 17),
                (wx - 14, wall_bot + 17),
            ],
            mix(NIGHT_D, WINDOW, 0.28),
        )

    return on_field(scene, ground(NIGHT))


# ============================================================ candidate D ===


def draw_wordmark_split(
    d, cx: float, cy: float, scale: int, col: Color
) -> tuple[float, float]:
    top, bot = "MEAN", "WHILE"
    wt, wb = text_width(top, scale), text_width(bot, scale)
    line_h = 7 * scale
    gap = 2 * scale
    y0 = cy - (line_h * 2 + gap) / 2
    draw_text(d, top, round(cx - wt / 2), round(y0), scale, col)
    draw_text(d, bot, round(cx - wb / 2), round(y0 + line_h + gap), scale, col)
    return (max(wt, wb), line_h * 2 + gap)


def cand_letters() -> Image.Image:
    scene, d = canvas()
    # Scale 4 is the floor for legibility: at scale 3 a stroke is 1.1px once the
    # icon is 48px and the word turns to grey mush; at scale 5 WHILE overruns the
    # frame. At 4 the strokes are 1.5px and the word still reads. That leaves no
    # room for the comic caption rule this direction started with, so the rule is
    # dropped rather than shrunk - it is the wordmark that carries the device.
    draw_wordmark_split(d, GRID / 2, GRID / 2, 4, INK)
    return on_field(scene, ground(SAND))


# ============================================== wordmark, banner, export ===
# "More branding" past a 64px square. The formats are the ones this family's
# own store surface already has: the icon, and Modrinth's featured gallery
# image, which is rendered 456x160 in list rows and 260x130 on the project page
# from a 2:1 upload (STORE_DESCRIPTION_GUIDE). It matters more here than for the
# other mods: Meanwhile is server-side with no screen, no block and no item, so
# there is no in-game screenshot that shows it working. A drawn banner is the
# only gallery image this mod can honestly have.

FIELDS = {
    "A_room": (OCHRE, INK),
    "B_distance": (DUSK_HI, SMOKE),
    "C_house": (NIGHT, WINDOW),
    "D_letters": (SAND, INK),
}


def wide_canvas(w_units: int, h_units: int):
    img = Image.new("RGBA", (w_units * U, h_units * U), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def render_wordmark(name: str) -> Image.Image:
    """Icon beside the name, on the direction's own field. 1280x320."""
    w_u, h_u = 320, 80
    field, ink = FIELDS[name]
    img, d = wide_canvas(w_u, h_u)
    d.rectangle([0, 0, w_u * U - 1, h_u * U - 1], fill=field)

    icon_u = 60
    icon = CANDIDATES[name]().resize((icon_u * U, icon_u * U), Image.LANCZOS)
    img.alpha_composite(icon, (10 * U, 10 * U))

    scale = 4
    word = "MEANWHILE"
    tw = text_width(word, scale)
    draw_text(d, word, 10 + icon_u + 14, (h_u - 7 * scale) // 2, scale, ink)
    del tw
    return img.resize((1280, 320), Image.LANCZOS)


def render_banner(name: str) -> Image.Image:
    """Modrinth featured image, 2:1. Uploaded at 1456x728."""
    w_u, h_u = 256, 128
    field, ink = FIELDS[name]
    img, d = wide_canvas(w_u, h_u)
    d.rectangle([0, 0, w_u * U - 1, h_u * U - 1], fill=field)

    if name == "A_room":
        draw_room(d, 66, 54)
        draw_text(d, "MEANWHILE", 138, 57, 2, ink)
    elif name == "B_distance":
        draw_distance(d, w_u, 150, 1.6)
        draw_text(d, "MEANWHILE", 16, 16, 2, ink)
    elif name == "C_house":
        rect(d, 0, 106, w_u, h_u, NIGHT_D)
        draw_house(d, 14, 112, 56, 106)
        for wx in (30, 82):
            poly(d, [(wx, 106), (wx + 14, 106), (wx + 21, 114), (wx - 7, 114)],
                 mix(NIGHT_D, WINDOW, 0.60))
            poly(d, [(wx - 7, 114), (wx + 21, 114), (wx + 28, 123), (wx - 14, 123)],
                 mix(NIGHT_D, WINDOW, 0.28))
        draw_text(d, "MEANWHILE", 136, 57, 2, ink)
    else:
        scale = 3
        word = "MEANWHILE"
        draw_text(d, word, (w_u - text_width(word, scale)) // 2,
                  (h_u - 7 * scale) // 2, scale, ink)
        # the caption rule the icon had no room for; a banner has the room
        d.rectangle([10 * U, 10 * U, (w_u - 10) * U - 1, (h_u - 10) * U - 1],
                    outline=ink, width=2 * U)
    return img.resize((1456, 728), Image.LANCZOS)


# ------------------------------------------------- sheets for the choice ---

CLAIMS = {
    "A_room": "one chunk of your base, still working with nobody standing in it",
    "B_distance": "the part of the world you walked out of keeps running",
    "C_house": "the lights are on, nobody is home, and the work goes on",
    "D_letters": "meanwhile: elsewhere, at the same time - the mod is the cutaway",
}


def label_font(size: int):
    for path in ("C:/Windows/Fonts/segoeui.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def metrics(icon: Image.Image, field: Color) -> tuple[float, float]:
    """Subject bbox share of the side, and the share of pixels that are subject."""
    px = icon.convert("RGBA").load()
    w, h = icon.size
    xs, ys, n = [], [], 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 250:  # the rounded field's own soft edge is not subject
                continue
            if abs(r - field[0]) + abs(g - field[1]) + abs(b - field[2]) < 24:
                continue
            xs.append(x)
            ys.append(y)
            n += 1
    if not xs:
        return (0.0, 0.0)
    bbox = max(max(xs) - min(xs), max(ys) - min(ys)) / w
    return (bbox, n / (w * h))


def contact_sheet(icons: dict[str, Image.Image]) -> Image.Image:
    TILE, PAD, LABEL = 340, 22, 58
    cols = 2
    rows_n = (len(icons) + cols - 1) // cols
    sheet = Image.new("RGB", (PAD + cols * (TILE + PAD),
                              PAD + rows_n * (TILE + LABEL + PAD)), (52, 54, 60))
    sd = ImageDraw.Draw(sheet)
    f_title, f_claim = label_font(17), label_font(14)
    for i, (name, icon) in enumerate(icons.items()):
        cx = PAD + (i % cols) * (TILE + PAD)
        cy = PAD + (i // cols) * (TILE + LABEL + PAD) + LABEL
        sheet.paste(icon.resize((TILE, TILE), Image.LANCZOS).convert("RGB"), (cx, cy))
        x = cx + TILE - 6
        for size in (96, 64, 48):
            x -= size
            ins = icon.resize((size, size), Image.LANCZOS).convert("RGB")
            sheet.paste(ins, (x, cy + TILE - size - 6))
            sd.rectangle([x, cy + TILE - size - 6, x + size, cy + TILE - 6],
                         outline=(140, 140, 140))
            x -= 8
        bbox, cov = metrics(icon, FIELDS[name][0])
        sd.text((cx + 2, cy - LABEL + 2), f"{name}   bbox {bbox:.0%}   coverage {cov:.0%}",
                fill=(238, 238, 238), font=f_title)
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
            sd.text((cx + 2, cy - LABEL + 24 + k * 16), ln, fill=(178, 180, 186), font=f_claim)
    return sheet


def size_check(icons: dict[str, Image.Image]) -> Image.Image:
    """64 and 48 on both list backgrounds: CurseForge is light, Modrinth is dark."""
    GUT, ROW = 26, 64 + 48 + 34
    w = GUT + len(icons) * (64 + GUT)
    img = Image.new("RGB", (w, 2 * (ROW + GUT) + GUT), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, ROW + GUT, w, img.height], fill=(30, 31, 34))
    f = label_font(12)
    for band, y0, ink in ((0, GUT, (40, 40, 40)), (1, ROW + 2 * GUT, (225, 225, 228))):
        for i, (name, icon) in enumerate(icons.items()):
            x = GUT + i * (64 + GUT)
            img.paste(icon.resize((64, 64), Image.LANCZOS).convert("RGB"), (x, y0))
            img.paste(icon.resize((48, 48), Image.LANCZOS).convert("RGB"),
                      (x + 8, y0 + 64 + 10))
            d.text((x, y0 + 64 + 48 + 16), name.split("_")[1], fill=ink, font=f)
        del band
    return img


# ================================================================= output ===

CANDIDATES = {
    "A_room": cand_room,
    "B_distance": cand_distance,
    "C_house": cand_house,
    "D_letters": cand_letters,
}


def main() -> None:
    CAND.mkdir(parents=True, exist_ok=True)
    (BRANDING / "wordmark-candidates").mkdir(exist_ok=True)
    (BRANDING / "banner-candidates").mkdir(exist_ok=True)
    icons: dict[str, Image.Image] = {}
    for name, fn in CANDIDATES.items():
        icon = finish(fn())
        for size in (512, 256, 128, 64, 48):
            out = icon if size == 512 else icon.resize((size, size), Image.LANCZOS)
            out.save(CAND / f"meanwhile_{name}_{size}.png")
        render_wordmark(name).save(
            BRANDING / "wordmark-candidates" / f"meanwhile_{name}_wordmark.png")
        render_banner(name).save(
            BRANDING / "banner-candidates" / f"meanwhile_{name}_banner_1456x728.png")
        print(f"wrote icon/wordmark/banner for {name}")
        icons[name] = icon
    contact_sheet(icons).save(CAND / "_contact_sheet.png")
    size_check(icons).save(CAND / "_small_size_check.png")
    for name, icon in icons.items():
        bbox, cov = metrics(icon, FIELDS[name][0])
        print(f"{name:12s} bbox {bbox:.0%}  coverage {cov:.0%}")


if __name__ == "__main__":
    main()
