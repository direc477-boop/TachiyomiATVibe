import hashlib
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/pages")
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/scratch/mcv2_576x832/rawkuma_experiment")
WIDTH, HEIGHT = 576, 832


def input_tensor(path):
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
    x[0, 0] = 1
    top, left = (HEIGHT - ch) // 2, (WIDTH - cw) // 2
    x[0, 0, top:top + ch, left:left + cw] = work.astype(np.float32) / 255
    return x, score


def main():
    files = sorted(p for p in SOURCE.rglob("*") if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})
    seen = set()
    unique = []
    for p in files:
        digest = hashlib.sha256(p.read_bytes()).hexdigest()
        if digest not in seen:
            seen.add(digest)
            unique.append((p, digest))
    if len(unique) < 10:
        raise ValueError("At least ten distinct images are required.")
    OUT.mkdir(parents=True, exist_ok=True)
    groups = {"calibration": [], "evaluation": []}
    manifest = []
    for index, (path, digest) in enumerate(unique):
        group = "evaluation" if index % 5 == 0 else "calibration"
        tensor, score = input_tensor(path)
        groups[group].append(tensor)
        manifest.append({"file": str(path.relative_to(SOURCE)), "sha256": digest, "group": group, "gate_score": score})
        print(f"{index + 1}/{len(unique)} {group}: {path.name} gate={score:.3f}", flush=True)
    for group, tensors in groups.items():
        np.savez_compressed(OUT / f"{group}.npz", inputs=np.stack(tensors))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Prepared {len(groups['calibration'])} calibration and {len(groups['evaluation'])} held-out pages", flush=True)


if __name__ == "__main__":
    main()
