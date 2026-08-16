# -*- coding: utf-8 -*-
"""Measures the three anchors kura named as the round-13 design targets
(LOGO_PLAYBOOK.md's "kura's合否基準": sodium/lithium = brand-strong,
architectury-api = function-guessable-adjacent, all three are the
multi-colour/bold-flat family this round is drawing toward). Ran once,
inline, to produce the numbers cited in render_meanwhile_icons_r13.py's
docstring; committed here so that citation resolves to a real script
instead of a session-only one-off.

Method: sample the four corner pixels as the background colour, mask
every pixel whose Euclidean RGB distance from that colour exceeds 0.12
(and alpha > 10), then report the mask's bounding box, margins, fill
ratio, and count of connected components with more than 0.1% of the
canvas area. This is the same method render_meanwhile_icons_r13.py's
measure_mark() uses for its own candidates.
"""

from __future__ import annotations

import colorsys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = Path(__file__).resolve().parent
ICONS = ROOT / "gate0" / "icons"

ANCHORS = ["sodium_orig.webp", "lithium_orig.webp", "architectury-api_orig.webp"]


def analyze(path: Path) -> None:
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    arr = np.array(img).astype(np.float32)
    rgb = arr[:, :, :3] / 255.0
    a = arr[:, :, 3]

    corners = [rgb[2, 2], rgb[2, w - 3], rgb[h - 3, 2], rgb[h - 3, w - 3]]
    bg = np.mean(corners, axis=0)
    hsv_bg = colorsys.rgb_to_hsv(*bg)
    print(f"{path.name}  size={w}x{h}")
    print(
        f"  bg RGB={tuple((bg * 255).astype(int))}"
        f" HSV=H{hsv_bg[0] * 360:.0f} S{hsv_bg[1] * 100:.0f}% V{hsv_bg[2] * 100:.0f}%"
    )

    diff = np.linalg.norm(rgb - bg[None, None, :], axis=2)
    mask = (diff > 0.12) & (a > 10)
    ys, xs = np.where(mask)
    if len(xs) == 0:
        print("  no subject found")
        return

    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    bbox_w, bbox_h = (x1 - x0 + 1) / w, (y1 - y0 + 1) / h
    margin_l, margin_r = x0 / w, (w - 1 - x1) / w
    margin_t, margin_b = y0 / h, (h - 1 - y1) / h
    print(
        f"  subject bbox: w={bbox_w * 100:.1f}% h={bbox_h * 100:.1f}%"
        f"  margins L{margin_l * 100:.1f} R{margin_r * 100:.1f}"
        f" T{margin_t * 100:.1f} B{margin_b * 100:.1f}"
    )

    subj_rgb = np.median(rgb[mask], axis=0)
    hsv_s = colorsys.rgb_to_hsv(*subj_rgb)
    print(
        f"  subject RGB={tuple((subj_rgb * 255).astype(int))}"
        f" HSV=H{hsv_s[0] * 360:.0f} S{hsv_s[1] * 100:.0f}% V{hsv_s[2] * 100:.0f}%"
    )
    fill_ratio = mask.sum() / (w * h)
    print(f"  fill ratio (subject px / canvas px) = {fill_ratio * 100:.1f}%")

    lbl, n = ndimage.label(mask)
    sizes = ndimage.sum(mask, lbl, range(1, n + 1))
    big = int((sizes > w * h * 0.001).sum())
    print(f"  connected components: total={n}  significant(>0.1% area)={big}")


def analyze_by_hue(path: Path, hue_lo: float, hue_hi: float, sat_min: float) -> None:
    """architectury-api's field is a dark vignette gradient, not a flat
    colour, so the corner-diff method above (correct for sodium/lithium's
    flat fields) also catches the gradient itself as 'subject' and
    overstates the bbox/fill. This isolates the actual crane by hue/
    saturation instead, which is what the round-13 docstring's
    architectury numbers come from."""
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    arr = np.array(img).astype(np.float32) / 255.0
    rgb = arr[:, :, :3]
    hsv = np.zeros((h, w, 3), dtype=np.float32)
    for i in range(h):
        for j in range(w):
            hsv[i, j] = colorsys.rgb_to_hsv(*rgb[i, j, :3])
    mask = (
        (hsv[:, :, 0] * 360 > hue_lo)
        & (hsv[:, :, 0] * 360 < hue_hi)
        & (hsv[:, :, 1] > sat_min)
    )
    ys, xs = np.where(mask)
    if len(xs) == 0:
        print(f"{path.name} (hue {hue_lo}-{hue_hi}): no subject found")
        return
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    bbox_w, bbox_h = (x1 - x0 + 1) / w, (y1 - y0 + 1) / h
    margin_l, margin_r = x0 / w, (w - 1 - x1) / w
    margin_t, margin_b = y0 / h, (h - 1 - y1) / h
    subj_rgb = np.median(rgb[mask], axis=0)
    hsv_s = colorsys.rgb_to_hsv(*subj_rgb)
    fill_ratio = mask.sum() / (w * h)
    lbl, n = ndimage.label(mask)
    sizes = ndimage.sum(mask, lbl, range(1, n + 1))
    big = int((sizes > w * h * 0.001).sum())
    print(f"{path.name}  (hue-isolated subject, not the corner-diff field)")
    print(
        f"  subject bbox: w={bbox_w * 100:.1f}% h={bbox_h * 100:.1f}%"
        f"  margins L{margin_l * 100:.1f} R{margin_r * 100:.1f}"
        f" T{margin_t * 100:.1f} B{margin_b * 100:.1f}"
    )
    print(
        f"  subject RGB={tuple((subj_rgb * 255).astype(int))}"
        f" HSV=H{hsv_s[0] * 360:.0f} S{hsv_s[1] * 100:.0f}% V{hsv_s[2] * 100:.0f}%"
    )
    print(f"  fill ratio (subject px / canvas px) = {fill_ratio * 100:.1f}%")
    print(f"  connected components: total={n}  significant(>0.1% area)={big}")


def main() -> None:
    for name in ANCHORS:
        analyze(ICONS / name)
    print()
    analyze_by_hue(
        ICONS / "architectury-api_orig.webp", hue_lo=5, hue_hi=45, sat_min=0.4
    )


if __name__ == "__main__":
    main()
