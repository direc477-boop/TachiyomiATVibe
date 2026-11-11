#!/usr/bin/env python3
"""Stage 1: Preprocess all 33 pages of Chapter 34 with FFDNet + resize_pad."""

import json
import os
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE\models_local\qweasdd-manga-colorization-v2")
from denoising.denoiser import FFDNetDenoiser
from utils.utils import resize_pad

CH34_DIR = Path(r"C:\Users\ADMIN\Downloads\ch34_compare")
SRC_DIR = CH34_DIR / "sources"
PAIRING_FILE = CH34_DIR / "pairing.json"

OUT_NPZ = CH34_DIR / "ch34_inputs.npz"
OUT_PADS = CH34_DIR / "ch34_pads.json"

def main():
    print("=== Stage 1: Preparing Chapter 34 Input Tensors ===", flush=True)
    t0 = time.time()

    with open(PAIRING_FILE) as f:
        pairing = json.load(f)
    pairs = sorted(pairing, key=lambda x: x["app_page"])

    weights_dir = r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE\models_local\qweasdd-manga-colorization-v2\denoising\models"
    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=weights_dir)
    print("FFDNet denoiser loaded.", flush=True)

    tensors = []
    pads = []

    for item in pairs:
        page_num = item["app_page"]
        t_idx = item["torch_index"]
        src_path = SRC_DIR / f"src_{t_idx:02d}.jpg"

        t_p = time.time()
        im = Image.open(src_path).convert("RGB")
        rgb = np.array(im)
        orig_h, orig_w = rgb.shape[:2]

        # FFDNet denoise
        denoised = denoiser.get_denoised_image(rgb, sigma=25)

        # resize_pad to 576
        work, pad = resize_pad(denoised, size=576)

        # Canvas 576x832
        canvas = np.ones((832, 576), dtype=np.uint8) * 255
        ch = min(832, work.shape[0])
        cw = min(576, work.shape[1])
        canvas[:ch, :cw] = work[:ch, :cw, 0]

        tensor = np.zeros((1, 5, 832, 576), dtype=np.float32)
        tensor[0, 0] = canvas.astype(np.float32) / 255.0

        tensors.append(tensor)
        pads.append({
            "page": page_num,
            "torch_index": t_idx,
            "orig_w": orig_w,
            "orig_h": orig_h,
            "pad": list(pad) if pad else [0, 0],
            "crop_h": 832 - pad[0] if pad and pad[0] > 0 else 832,
            "crop_w": 576 - pad[1] if pad and pad[1] > 0 else 576,
        })
        if page_num % 5 == 0 or page_num == len(pairs):
            print(f"  Processed page {page_num:02d}/33 in {time.time()-t_p:.2f}s", flush=True)

    tensors_stack = np.stack(tensors, axis=0) # (33, 1, 5, 832, 576)
    np.savez_compressed(OUT_NPZ, inputs=tensors_stack)
    with open(OUT_PADS, "w", encoding="utf-8") as f:
        json.dump(pads, f, indent=2)

    print(f"Stage 1 done in {time.time()-t0:.1f}s. Saved {OUT_NPZ} ({OUT_NPZ.stat().st_size:,} bytes)", flush=True)

if __name__ == "__main__":
    main()
