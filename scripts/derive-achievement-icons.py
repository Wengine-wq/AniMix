"""Temporary icons for achievements without painted art: an existing badge plus a round sticker.

Run from the repository root: python scripts/derive-achievement-icons.py
Replace a derived icon with painted art by dropping ach_<id>.png into composeResources/drawable.
"""
from PIL import Image, ImageDraw, ImageFont
import os

DRAWABLE = "shared/src/commonMain/composeResources/drawable"
FONT = "shared/src/commonMain/composeResources/font/inter.ttf"

# id -> (base badge, sticker text, sticker colour)
DERIVED = {
    "secret_hentai_filter": ("catalog_filter_discovery", "18+", (255, 136, 157)),
    "secret_ecchi_filter": ("catalog_filter_discovery", "16+", (255, 180, 200)),
    "secret_redo_healer": ("ten_search_discoveries", "+", (110, 200, 140)),
    "legacy_lists": ("first_completed", "2.x", (255, 158, 198)),
}


def sticker(base: Image.Image, text: str, colour) -> Image.Image:
    image = base.convert("RGBA").copy()
    size = image.size[0]
    radius = int(size * 0.2)
    cx, cy = size - radius - 6, size - radius - 6
    draw = ImageDraw.Draw(image)
    draw.ellipse((cx - radius - 5, cy - radius - 5, cx + radius + 5, cy + radius + 5), fill=(26, 26, 31, 255))
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=colour + (255,))
    font_size = int(radius * (1.3 if len(text) <= 1 else 0.95 if len(text) == 2 else 0.78))
    font = ImageFont.truetype(FONT, font_size)
    try:
        font.set_variation_by_name("Bold")
    except Exception:
        pass  # a static font is fine too
    box = draw.textbbox((0, 0), text, font=font)
    draw.text((cx - (box[0] + box[2]) / 2, cy - (box[1] + box[3]) / 2), text, font=font, fill=(26, 26, 31, 255))
    return image


for target, (base, text, colour) in DERIVED.items():
    source = os.path.join(DRAWABLE, f"ach_{base}.png")
    sticker(Image.open(source), text, colour).save(os.path.join(DRAWABLE, f"ach_{target}.png"), optimize=True)
    print("ok", target)
