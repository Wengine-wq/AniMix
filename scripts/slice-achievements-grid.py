"""Projection-based slicing for an atlas whose badges touch: cut at the emptiest lines near the grid."""
import json, sys
import numpy as np
from PIL import Image

root, out, sheet = sys.argv[1], sys.argv[2], sys.argv[3]
catalog = json.load(open(f"{root}/achievements.ru.json", encoding="utf-8"))
info = catalog["atlases"][sheet]
items = {(a["atlas"]["row"], a["atlas"]["column"]): a["id"] for a in catalog["achievements"] if a["atlas"]["sheet"] == sheet}
image = Image.open(f"{root}/{info['file']}").convert("RGBA")
mask = np.array(image)[:, :, 3] > 24

def cuts(profile, parts, lo, hi):
    length = hi - lo
    result = [lo]
    for i in range(1, parts):
        expected = lo + length * i / parts
        window = int(length / parts * 0.22)
        a, b = int(expected - window), int(expected + window)
        segment = profile[a:b]
        # The emptiest line; among equals, the one nearest the expected cut.
        best = min(range(len(segment)), key=lambda k: (segment[k], abs(a + k - expected)))
        result.append(a + best)
    result.append(hi)
    return result

ys = np.where(mask.any(axis=1))[0]; xs = np.where(mask.any(axis=0))[0]
rows = cuts(mask.sum(axis=1), info["rows"], ys[0], ys[-1] + 1)
for r in range(info["rows"]):
    band = mask[rows[r]:rows[r + 1]]
    cols = cuts(band.sum(axis=0), info["columns"], xs[0], xs[-1] + 1)
    for c in range(info["columns"]):
        ident = items.get((r, c))
        if ident is None: continue
        cell = band[:, cols[c]:cols[c + 1]]
        cy, cx = np.where(cell)
        x0, x1 = cols[c] + cx.min(), cols[c] + cx.max() + 1
        y0, y1 = rows[r] + cy.min(), rows[r] + cy.max() + 1
        crop = np.array(image)[y0:y1, x0:x1].copy()
        # Clear pixels that belong to the neighbouring cells (outside this cell's cut lines).
        crop_img = Image.fromarray(crop)
        side = max(crop_img.width, crop_img.height); pad = int(side * 0.06)
        canvas = Image.new("RGBA", (side + 2 * pad, side + 2 * pad), (0, 0, 0, 0))
        canvas.paste(crop_img, ((side - crop_img.width) // 2 + pad, (side - crop_img.height) // 2 + pad))
        canvas.resize((256, 256), Image.LANCZOS).save(f"{out}/ach_{ident}.png", optimize=True)
        print(r, c, ident, (x0, y0, x1, y1))
