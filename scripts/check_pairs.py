#!/usr/bin/env python3
import json
from pathlib import Path

src_dir = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare/sources")
torch_dir = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare/torch_out")
app_dir = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare/app_out/Chapter 34_images")
pairs_file = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare/pairing.json")

sources = sorted(src_dir.glob("*.jpg"))
torchs = sorted(torch_dir.glob("*.png"))
apps = sorted([f for f in app_dir.glob("*.jpg") if not f.name.endswith(".json")])

print(f"Sources count: {len(sources)}")
print(f"Torches count: {len(torchs)}")
print(f"Apps count: {len(apps)}")

with open(pairs_file) as f:
    pairs = json.load(f)

pairs_by_page = sorted(pairs, key=lambda x: x["app_page"])
print("First 5 pairs by page:")
for p in pairs_by_page[:5]:
    t_idx = p["torch_index"]
    print(f"  Page {p['app_page']:02d}: src_{t_idx:02d}.jpg -> torch_{t_idx:02d}.png (PSNR={p['psnr']} dB, old_sat={p['app_sat']:.1f}, torch_sat={p['torch_sat']:.1f})")
