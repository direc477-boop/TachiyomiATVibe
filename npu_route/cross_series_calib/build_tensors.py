import hashlib
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

SPLIT_FILE = Path("npu_route/cross_series_calib/dataset_split.json")
OUT_DIR = Path("npu_route/cross_series_calib/tensors")
OUT_DIR.mkdir(parents=True, exist_ok=True)

SYNTH_SOURCE = Path(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_576x832\rawkuma_experiment\synthetic_source.npz")
SASA_PATH = Path(r"C:\Users\ADMIN\Downloads\sasa.png")

WIDTH, HEIGHT = 576, 832

def input_tensor(path: Path):
    image = Image.open(path).convert("L")
    gray = np.asarray(image, dtype=np.uint8)
    h, w = gray.shape
    thumb = cv2.resize(gray, (384, max(1, round(h * 384 / w))), interpolation=cv2.INTER_AREA)
    unit = thumb.astype(np.float32) / 255.0
    blur = cv2.GaussianBlur(unit, (7, 7), 3, borderType=cv2.BORDER_REFLECT_101)
    mask = (unit > 0.25) & (unit < 0.75)
    score = float(np.mean(np.where(mask, (unit - blur) ** 2, 0)) * 1000)
    scale = min(WIDTH / w, HEIGHT / h)
    cw, ch = max(1, round(w * scale)), max(1, round(h * scale))
    work = cv2.resize(gray, (cw, ch), interpolation=cv2.INTER_AREA)
    if score >= 2.5:
        filtered = cv2.bilateralFilter(work, 5, 35, 35, borderType=cv2.BORDER_REFLECT_101)
        work = np.where(work < 35, work, filtered).astype(np.uint8)
    x = np.zeros((1, 5, HEIGHT, WIDTH), dtype=np.float32)
    x[0, 0] = 1.0
    top, left = (HEIGHT - ch) // 2, (WIDTH - cw) // 2
    x[0, 0, top:top + ch, left:left + cw] = work.astype(np.float32) / 255.0
    return x, score

def main():
    with open(SPLIT_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    split = data["split"]
    calib_tensors = []
    heldout_tensors = []
    metadata = []

    print(f"Processing {len(split)} images to tensors...", flush=True)
    for i, item in enumerate(split):
        p = Path(item["path"])
        tensor, score = input_tensor(p)
        item_meta = dict(item)
        item_meta["gate_score"] = score
        item_meta["tensor_sha256"] = hashlib.sha256(tensor.tobytes()).hexdigest()
        metadata.append(item_meta)

        if item["group"] == "calibration":
            calib_tensors.append(tensor)
        else:
            heldout_tensors.append(tensor)

        if (i + 1) % 10 == 0 or (i + 1) == len(split):
            print(f"  Processed {i+1}/{len(split)}: {item['rel_file']} (score={score:.2f})", flush=True)

    # 1. Save manga-only calibration set (79 pages)
    calib_arr = np.stack(calib_tensors)
    np.savez_compressed(OUT_DIR / "calibration_manga_only.npz", inputs=calib_arr)
    print(f"Saved calibration_manga_only.npz: shape {calib_arr.shape}", flush=True)

    # 2. Save mixed calibration set: 4 synthetic samples + 79 manga pages = 83 samples
    synth_inputs = np.load(SYNTH_SOURCE)["inputs"][:4]
    print(f"Loaded {len(synth_inputs)} synthetic samples from {SYNTH_SOURCE.name}", flush=True)
    mixed_arr = np.concatenate([synth_inputs, calib_arr], axis=0)
    np.savez_compressed(OUT_DIR / "calibration_mixed.npz", inputs=mixed_arr)
    print(f"Saved calibration_mixed.npz: shape {mixed_arr.shape}", flush=True)

    # 3. Save held-out evaluation set (20 pages)
    heldout_arr = np.stack(heldout_tensors)
    np.savez_compressed(OUT_DIR / "held_out_evaluation.npz", inputs=heldout_arr)
    print(f"Saved held_out_evaluation.npz: shape {heldout_arr.shape}", flush=True)

    # 4. Save sasa held-out tensor
    sasa_tensor, sasa_score = input_tensor(SASA_PATH)
    np.savez_compressed(OUT_DIR / "sasa_evaluation.npz", inputs=sasa_tensor)
    print(f"Saved sasa_evaluation.npz: shape {sasa_tensor.shape} (score={sasa_score:.2f})", flush=True)

    (OUT_DIR / "tensors_manifest.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print("All tensors generated successfully.")

if __name__ == "__main__":
    main()
