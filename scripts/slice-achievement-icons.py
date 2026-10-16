import json, os, sys
from PIL import Image

root = sys.argv[1]
src = os.path.join(root, "design", "achievements")
out = os.path.join(root, "shared", "src", "commonMain", "composeResources", "drawable")
data = json.load(open(os.path.join(src, "achievements.ru.json"), encoding="utf-8"))
sheets = data["atlases"]


def bands(profile, count, min_gap):
    """Split a 1-D alpha profile into `count` occupied runs."""
    runs, start = [], None
    for i, v in enumerate(profile):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append([start, i]); start = None
    if start is not None:
        runs.append([start, len(profile)])
    # merge runs separated by tiny gaps until we have `count`
    while len(runs) > count:
        gaps = [(runs[i + 1][0] - runs[i][1], i) for i in range(len(runs) - 1)]
        g, i = min(gaps)
        runs[i] = [runs[i][0], runs[i + 1][1]]; del runs[i + 1]
    return runs


cells = {}
for key, sheet in sheets.items():
    im = Image.open(os.path.join(src, sheet["file"])).convert("RGBA")
    w, h = im.size
    cw, ch = w / sheet["columns"], h / sheet["rows"]
    grid = {}
    solid = im.split()[3].point(lambda v: 255 if v > 110 else 0)
    sp = solid.load()
    row_runs = bands([any(sp[x, y] for x in range(0, w, 3)) for y in range(h)], sheet["rows"], 4)
    if len(row_runs) == sheet["rows"]:
        ok = True
        for r, (y0, y1) in enumerate(row_runs):
            col_runs = bands([any(sp[x, y] for y in range(y0, y1, 3)) for x in range(w)], sheet["columns"], 4)
            if len(col_runs) != sheet["columns"]:
                ok = False; break
            for c, (x0, x1) in enumerate(col_runs):
                bb = solid.crop((x0, y0, x1, y1)).getbbox()
                grid[(c, r)] = (x0 + bb[0], y0 + bb[1], x0 + bb[2], y0 + bb[3])
        if ok:
            cells[key] = (im, grid)
            print(key, "content-sliced")
            continue
        grid = {}
    # Fallback: split the area that actually holds icons into an even grid.
    ox, oy, ex, ey = solid.getbbox()
    cw, ch = (ex - ox) / sheet["columns"], (ey - oy) / sheet["rows"]
    for c in range(sheet["columns"]):
        for r in range(sheet["rows"]):
            mx, my = cw * 0.03, ch * 0.03  # keep neighbours' edges out
            cell = (int(ox + c * cw + mx), int(oy + r * ch + my), int(ox + (c + 1) * cw - mx), int(oy + (r + 1) * ch - my))
            alpha = im.crop(cell).split()[3].point(lambda v: 255 if v > 110 else 0)
            bb = alpha.getbbox()
            grid[(c, r)] = (cell[0] + bb[0], cell[1] + bb[1], cell[0] + bb[2], cell[1] + bb[3]) if bb else cell
    cells[key] = (im, grid)

os.makedirs(out, exist_ok=True)
for ach in data["achievements"]:
    at = ach["atlas"]
    im, grid = cells[at["sheet"]]
    box = grid[(at["column"], at["row"])]
    icon = im.crop(box)
    s = max(icon.size)
    sq = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    sq.paste(icon, ((s - icon.size[0]) // 2, (s - icon.size[1]) // 2))
    sq.resize((256, 256), Image.LANCZOS).save(os.path.join(out, f"ach_{ach['id']}.png"), optimize=True)
print("saved", len(data["achievements"]))

# Contact sheet to eyeball the slicing.
sheet = Image.new("RGBA", (64 * 9, 64 * 6), (20, 20, 25, 255))
for i, ach in enumerate(data["achievements"]):
    ic = Image.open(os.path.join(out, f"ach_{ach['id']}.png")).resize((60, 60))
    sheet.alpha_composite(ic, ((i % 9) * 64 + 2, (i // 9) * 64 + 2))
sheet.save(sys.argv[2])
