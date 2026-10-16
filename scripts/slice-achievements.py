"""Re-slice achievement badges from their atlases by the alpha channel instead of a uniform grid."""
import json
import sys
from collections import deque

import numpy as np
from PIL import Image

root = sys.argv[1]  # design/achievements
out = sys.argv[2]   # composeResources/drawable
preview = sys.argv[3]

catalog = json.load(open(f"{root}/achievements.ru.json", encoding="utf-8"))
SCALE = 4
SIZE = 256


def components(mask):
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    result = []
    for y in range(h):
        for x in range(w):
            if mask[y, x] and not seen[y, x]:
                queue = deque([(y, x)])
                seen[y, x] = True
                ys, xs = [], []
                while queue:
                    cy, cx = queue.popleft()
                    ys.append(cy); xs.append(cx)
                    for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            queue.append((ny, nx))
                result.append((len(ys), min(xs), min(ys), max(xs) + 1, max(ys) + 1))
    return result


def slice_sheet(name, info, items):
    image = Image.open(f"{root}/{info['file']}").convert("RGBA")
    alpha = np.array(image)[:, :, 3]
    small = alpha[::SCALE, ::SCALE] > 24
    comps = components(small)
    count = info["rows"] * info["columns"]
    comps.sort(key=lambda c: -c[0])
    big = [list(c[1:]) for c in comps[:count]]
    # Sparkles and loose parts join the nearest badge.
    for area, x0, y0, x1, y1 in comps[count:]:
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        best = min(big, key=lambda b: ((b[0] + b[2]) / 2 - cx) ** 2 + ((b[1] + b[3]) / 2 - cy) ** 2)
        dist = max(best[0] - x1, x0 - best[2], best[1] - y1, y0 - best[3], 0)
        if dist <= 6:
            best[0], best[1], best[2], best[3] = min(best[0], x0), min(best[1], y0), max(best[2], x1), max(best[3], y1)
    # Row-major order: group by vertical centre into rows, then sort each row by x.
    big.sort(key=lambda b: (b[1] + b[3]) / 2)
    rows = [big[i * info["columns"]:(i + 1) * info["columns"]] for i in range(info["rows"])]
    ordered = [sorted(row, key=lambda b: b[0]) for row in rows]
    sheet_items = {(a["atlas"]["row"], a["atlas"]["column"]): a["id"] for a in items}
    tiles = []
    for r, row in enumerate(ordered):
        for c, (x0, y0, x1, y1) in enumerate(row):
            ident = sheet_items.get((r, c))
            if ident is None:
                continue
            box = [x0 * SCALE - SCALE, y0 * SCALE - SCALE, x1 * SCALE + SCALE, y1 * SCALE + SCALE]
            box = [max(0, box[0]), max(0, box[1]), min(image.width, box[2]), min(image.height, box[3])]
            crop = image.crop(box)
            # Keep only this badge: clear anything outside its own component region (neighbours' edges).
            side = max(crop.width, crop.height)
            pad = int(side * 0.06)
            canvas = Image.new("RGBA", (side + 2 * pad, side + 2 * pad), (0, 0, 0, 0))
            canvas.paste(crop, ((side - crop.width) // 2 + pad, (side - crop.height) // 2 + pad))
            tile = canvas.resize((SIZE, SIZE), Image.LANCZOS)
            tile.save(f"{out}/ach_{ident}.png", optimize=True)
            tiles.append(tile)
            print(name, r, c, ident, box)
    return tiles


all_tiles = []
for sheet, info in catalog["atlases"].items():
    items = [a for a in catalog["achievements"] if a["atlas"]["sheet"] == sheet]
    all_tiles += slice_sheet(sheet, info, items)

columns = 8
rows = (len(all_tiles) + columns - 1) // columns
board = Image.new("RGBA", (columns * 132, rows * 132), (24, 24, 30, 255))
for i, tile in enumerate(all_tiles):
    board.alpha_composite(tile.resize((128, 128)), ((i % columns) * 132 + 2, (i // columns) * 132 + 2))
board.save(preview)
