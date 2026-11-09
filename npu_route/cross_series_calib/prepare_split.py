import hashlib
import json
from pathlib import Path
from PIL import Image

ROOT = Path("npu_route/calibration/rawkuma")
OUT = Path("npu_route/cross_series_calib")
OUT.mkdir(parents=True, exist_ok=True)

files = sorted(p for p in ROOT.rglob("*") if p.is_file())
print(f"Total raw files: {len(files)}")

by_series = {}
verification_records = []

for p in files:
    rel = p.relative_to(ROOT)
    series = rel.parts[0]
    by_series.setdefault(series, []).append(p)
    sha256 = hashlib.sha256(p.read_bytes()).hexdigest()
    with Image.open(p) as img:
        fmt = img.format
        w, h = img.size
    verification_records.append({
        "file": str(rel),
        "series": series,
        "format": fmt,
        "width": w,
        "height": h,
        "sha256": sha256,
    })

print(f"Verified {len(verification_records)} files across {len(by_series)} series.")

# Previous held-out from drawing-saikyou
old_eval_names = {
    "chapter-202.412574\\001.jpg",
    "chapter-202.412574\\006.jpg",
    "chapter-202.412574\\011.jpg",
    "chapter-202.412574\\016.jpg",
    "chapter-203.415731\\003.jpg",
    "chapter-203.415731\\008.jpg",
    "chapter-203.415731\\013.jpg"
}

split = []
# 1. drawing-saikyou
for p in by_series["drawing-saikyou-mangaka-wa-oekaki-skill-de-isekai-musou-suru"]:
    rel = p.relative_to(ROOT / "drawing-saikyou-mangaka-wa-oekaki-skill-de-isekai-musou-suru")
    group = "held_out" if str(rel) in old_eval_names else "calibration"
    split.append({"path": str(p), "rel_file": str(p.relative_to(ROOT)), "series": "drawing-saikyou", "group": group, "is_previous_heldout": str(rel) in old_eval_names})

# 2. musket-girls (14 pages -> 3 held out: index 2, 6, 10)
for i, p in enumerate(by_series["musket-girls"]):
    group = "held_out" if i in (2, 6, 10) else "calibration"
    split.append({"path": str(p), "rel_file": str(p.relative_to(ROOT)), "series": "musket-girls", "group": group, "is_previous_heldout": False})

# 3. osananajimi (35 pages -> 7 held out: index 2, 7, 12, 17, 22, 27, 32)
for i, p in enumerate(by_series["osananajimi-ga-hoshii-to-tsubuyaitara-yoku-issho-ni-asobu-onna-tomodachi-no-yousu-ga-hen-ni-natta-nda-ga"]):
    group = "held_out" if i in (2, 7, 12, 17, 22, 27, 32) else "calibration"
    split.append({"path": str(p), "rel_file": str(p.relative_to(ROOT)), "series": "osananajimi", "group": group, "is_previous_heldout": False})

# 4. shinda-ishii (16 pages -> 3 held out: index 2, 7, 12)
for i, p in enumerate(by_series["shinda-ishii-no-taigun"]):
    group = "held_out" if i in (2, 7, 12) else "calibration"
    split.append({"path": str(p), "rel_file": str(p.relative_to(ROOT)), "series": "shinda-ishii", "group": group, "is_previous_heldout": False})

calib_count = sum(1 for x in split if x["group"] == "calibration")
heldout_count = sum(1 for x in split if x["group"] == "held_out")
print(f"Split results: {calib_count} calibration, {heldout_count} held-out (Total: {len(split)})")

manifest = {
    "summary": {
        "total_pages": len(split),
        "calibration_pages": calib_count,
        "held_out_pages": heldout_count,
        "series": {
            "drawing-saikyou": {"total": 34, "calib": 27, "held_out": 7},
            "musket-girls": {"total": 14, "calib": 11, "held_out": 3},
            "osananajimi": {"total": 35, "calib": 28, "held_out": 7},
            "shinda-ishii": {"total": 16, "calib": 13, "held_out": 3},
        }
    },
    "verification": verification_records,
    "split": split,
}

(OUT / "dataset_split.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print(f"Written split manifest to {OUT / 'dataset_split.json'}")
