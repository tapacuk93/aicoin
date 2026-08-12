#!/usr/bin/env python3
"""Build a makeicon manifest covering every AppIcon.appiconset under ~/src.

Reads each catalog's Contents.json as the source of truth for which sizes and
filenames that app expects, so we never invent or drop a slot. Entries with no
filename (aimusicgen) get one assigned and written back.
"""
import json, os, subprocess

ROOT = os.path.expanduser("~/src")
sets = subprocess.run(
    ["find", ROOT, "-name", "AppIcon.appiconset", "-maxdepth", "7"],
    capture_output=True, text=True).stdout.split()
sets = sorted(p for p in sets if "/.build/" not in p)

manifest, total = [], 0
for d in sets:
    cj = os.path.join(d, "Contents.json")
    data = json.load(open(cj))
    dirty = False
    for img in data["images"]:
        w, h = (float(x) for x in img["size"].split("x"))
        assert w == h, f"non-square slot {img['size']} in {d}"
        scale = int(img.get("scale", "1x").rstrip("x"))
        px = int(round(w * scale))
        name = img.get("filename")
        if not name:
            idiom = img.get("idiom", "universal")
            suffix = f"@{scale}x" if scale > 1 else ""
            name = f"icon-{idiom}-{img['size'].split('x')[0]}{suffix}.png"
            img["filename"] = name
            dirty = True
        manifest.append(f"icon\t{px}\t{os.path.join(d, name)}")
        total += 1
    if dirty:
        json.dump(data, open(cj, "w"), indent=2)
        print(f"  filled in filenames: {os.path.relpath(d, ROOT)}")
    print(f"{os.path.relpath(d, ROOT)}: {len(data['images'])} slots")

open("manifest.tsv", "w").write("\n".join(manifest) + "\n")
print(f"\n{len(sets)} catalogs, {total} images -> manifest.tsv")
