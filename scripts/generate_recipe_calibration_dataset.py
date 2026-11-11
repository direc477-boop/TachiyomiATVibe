#!/usr/bin/env python3
"""Generate calibration and held-out evaluation datasets for SM8650 NPU MCV2 (576x832)
using the author's original PyTorch recipe:
- FFDNet denoising (sigma=25, max-1200 long-side cap)
- Author resize_pad geometry (576 short-side, ceil height, bottom white pad to 832)
- Zero hint channels (channels 1-4 = 0.0)
- Calibration data sourced from the user's real reader disk cache
"""

import hashlib
import json
import os
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
import torch

# Paths
REPO_ROOT = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE")
MCV2_REPO = REPO_ROOT / "models_local" / "qweasdd-manga-colorization-v2"
sys.path.insert(0, str(MCV2_REPO))

from denoising.denoiser import FFDNetDenoiser
from utils.utils import resize_pad

CACHE_DIR = Path(r"C:\Users\ADMIN\Downloads\ch34_compare\chapter_disk_cache")
OUT_DIR = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe\saved_models\calibration_recipe")
OUT_DIR.mkdir(parents=True, exist_ok=True)

WIDTH, HEIGHT = 576, 832
NUM_CALIB = 80
NUM_HELDOUT = 20
TOTAL_SAMPLES = NUM_CALIB + NUM_HELDOUT


def process_page_to_tensor(denoiser: FFDNetDenoiser, img_path: Path):
    """Processes one page through the author's PyTorch recipe pipeline."""
    with Image.open(img_path) as im:
        im_rgb = np.array(im.convert("RGB"))

    orig_h, orig_w = im_rgb.shape[:2]

    # 1. Author's FFDNet denoising
    # Note: denoiser caps long side <= 1200 internally and normalizes
    denoised_bgr = denoiser.get_denoised_image(im_rgb, sigma=25)

    # 2. Author's resize_pad to 576
    work_gray, pad = resize_pad(denoised_bgr, size=WIDTH)

    # 3. Fit to fixed 576x832 NPU canvas (top-left aligned, white padded)
    canvas = np.ones((HEIGHT, WIDTH), dtype=np.uint8) * 255
    ch = min(HEIGHT, work_gray.shape[0])
    cw = min(WIDTH, work_gray.shape[1])
    canvas[:ch, :cw] = work_gray[:ch, :cw, 0]

    # 4. Pack into NCHW [1, 5, 832, 576] float32
    tensor = np.zeros((1, 5, HEIGHT, WIDTH), dtype=np.float32)
    tensor[0, 0] = canvas.astype(np.float32) / 255.0
    tensor[0, 1:5] = 0.0

    meta = {
        "file": img_path.name,
        "orig_w": orig_w,
        "orig_h": orig_h,
        "denoised_w": denoised_bgr.shape[1],
        "denoised_h": denoised_bgr.shape[0],
        "recipe_w": work_gray.shape[1],
        "recipe_h": work_gray.shape[0],
        "pad": list(pad) if pad else [0, 0],
        "ch0_mean": float(tensor[0, 0].mean()),
        "ch0_min": float(tensor[0, 0].min()),
        "ch0_max": float(tensor[0, 0].max()),
        "sha256": hashlib.sha256(tensor.tobytes()).hexdigest(),
    }
    return tensor, meta


def main():
    print(f"=== PyTorch Recipe Calibration Dataset Generator ===", flush=True)
    print(f"Source cache: {CACHE_DIR}", flush=True)
    print(f"Output dir:   {OUT_DIR}", flush=True)

    weights_dir = MCV2_REPO / "denoising" / "models"
    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=str(weights_dir))
    print(f"FFDNet denoiser loaded on CPU (weights: {weights_dir})", flush=True)

    all_files = sorted(CACHE_DIR.glob("*.0"))
    print(f"Found {len(all_files)} cached files", flush=True)

    # Verify they are valid images
    valid_files = []
    for f in all_files:
        try:
            with Image.open(f) as img:
                img.verify()
            valid_files.append(f)
        except Exception:
            continue

    print(f"Valid image count: {len(valid_files)}", flush=True)
    if len(valid_files) < TOTAL_SAMPLES:
        raise RuntimeError(f"Not enough valid images: {len(valid_files)} < {TOTAL_SAMPLES}")

    # Select samples evenly across the sorted dataset
    indices = np.linspace(0, len(valid_files) - 1, TOTAL_SAMPLES, dtype=int)
    calib_indices = indices[:NUM_CALIB]
    heldout_indices = indices[NUM_CALIB:]

    calib_files = [valid_files[i] for i in calib_indices]
    heldout_files = [valid_files[i] for i in heldout_indices]

    print(f"Selected {len(calib_files)} calibration pages and {len(heldout_files)} held-out pages", flush=True)

    # Process calibration set
    calib_tensors = []
    calib_meta = []
    t0 = time.time()
    for i, path in enumerate(calib_files):
        t_page = time.time()
        tensor, meta = process_page_to_tensor(denoiser, path)
        calib_tensors.append(tensor)
        calib_meta.append(meta)
        elapsed = time.time() - t_page
        if (i + 1) % 10 == 0 or (i + 1) == len(calib_files):
            print(f"  [Calib {i+1:02d}/{len(calib_files)}] {path.name}: {meta['orig_w']}x{meta['orig_h']} -> recipe {meta['recipe_w']}x{meta['recipe_h']} ({elapsed:.2f}s, mean={meta['ch0_mean']:.3f})", flush=True)

    calib_stack = np.concatenate(calib_tensors, axis=0) # shape (80, 5, 832, 576) or wait!
    # In LiteRT, Interpreter takes array of shape (1, 5, 832, 576) per sample!
    # calibration_mixed.npz had shape (83, 1, 5, 832, 576)
    calib_arr = np.stack(calib_tensors, axis=0) # shape (80, 1, 5, 832, 576)
    calib_path = OUT_DIR / "recipe_calibration_80.npz"
    np.savez_compressed(calib_path, inputs=calib_arr)
    print(f"Saved calibration dataset: {calib_path} (shape {calib_arr.shape}, {calib_path.stat().st_size:,} bytes)", flush=True)

    # Process held-out evaluation set
    heldout_tensors = []
    heldout_meta = []
    for i, path in enumerate(heldout_files):
        t_page = time.time()
        tensor, meta = process_page_to_tensor(denoiser, path)
        heldout_tensors.append(tensor)
        heldout_meta.append(meta)
        elapsed = time.time() - t_page
        if (i + 1) % 5 == 0 or (i + 1) == len(heldout_files):
            print(f"  [Heldout {i+1:02d}/{len(heldout_files)}] {path.name}: {meta['orig_w']}x{meta['orig_h']} -> recipe {meta['recipe_w']}x{meta['recipe_h']} ({elapsed:.2f}s, mean={meta['ch0_mean']:.3f})", flush=True)

    heldout_arr = np.stack(heldout_tensors, axis=0)
    heldout_path = OUT_DIR / "recipe_heldout_20.npz"
    np.savez_compressed(heldout_path, inputs=heldout_arr)
    print(f"Saved held-out dataset: {heldout_path} (shape {heldout_arr.shape}, {heldout_path.stat().st_size:,} bytes)", flush=True)

    # Save manifest
    manifest = {
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "total_calib_samples": len(calib_meta),
        "total_heldout_samples": len(heldout_meta),
        "calib_shape": list(calib_arr.shape),
        "heldout_shape": list(heldout_arr.shape),
        "calibration_samples": calib_meta,
        "heldout_samples": heldout_meta,
    }
    manifest_path = OUT_DIR / "recipe_manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"Saved manifest: {manifest_path}", flush=True)
    print(f"Total time: {time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
