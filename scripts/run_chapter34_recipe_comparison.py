#!/usr/bin/env python3
"""Run Chapter 34 through the New Recipe-Calibrated W8A8 model,
compute comparison metrics, and generate an interactive comparison artifact.
"""

import hashlib
import json
import math
import os
import shutil
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

# Import litert
sys.path.insert(0, "/scratch/venv/lib/python3.10/site-packages")
from ai_edge_litert.interpreter import Interpreter

# Import author repo
REPO_ROOT = Path("/mnt/c/Users/ADMIN/Documents/Coding Related/tachiyomiATVIBE")
MCV2_REPO = REPO_ROOT / "models_local" / "qweasdd-manga-colorization-v2"
sys.path.insert(0, str(MCV2_REPO))

from denoising.denoiser import FFDNetDenoiser
from utils.utils import resize_pad

CH34_DIR = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare")
SRC_DIR = CH34_DIR / "sources"
TORCH_DIR = CH34_DIR / "torch_out"
APP_DIR = CH34_DIR / "app_out" / "Chapter 34_images"
PAIRING_FILE = CH34_DIR / "pairing.json"

MODEL_PATH = Path("/scratch/mcv2_576x832/recipe_w8a8_run/mcv2_576x832_w8a8_recipe.tflite")

ARTIFACT_DIR = Path("/mnt/c/Users/ADMIN/.traycer/epics/7f9db2bc-6dee-4e24-89e3-af5704d3e90a/artifacts/ch34-recipe-w8a8-comparison")
IMAGES_DIR = ARTIFACT_DIR / "images"
IMAGES_DIR.mkdir(parents=True, exist_ok=True)

# Ink-aware merge constants
WHITE_X, WHITE_Y, WHITE_Z = 0.95047, 1.0, 1.08883
LAB_KAPPA, LAB_EPSILON = 903.3, 0.008856

def srgb_linearize(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)

def rgb_to_lab(rgb):
    lr, lg, lb = srgb_linearize(rgb[..., 0]), srgb_linearize(rgb[..., 1]), srgb_linearize(rgb[..., 2])
    x = 0.412453 * lr + 0.357580 * lg + 0.180423 * lb
    y = 0.212671 * lr + 0.715160 * lg + 0.072169 * lb
    z = 0.019334 * lr + 0.119193 * lg + 0.950227 * lb
    def f(val):
        return np.where(val > LAB_EPSILON, np.cbrt(val), (LAB_KAPPA * val + 16.0) / 116.0)
    fx, fy, fz = f(x / WHITE_X), f(y / WHITE_Y), f(z / WHITE_Z)
    L = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return np.stack([L, a, b], axis=-1)

def lab_to_rgb(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx, fz = fy + a / 500.0, fy - b / 200.0
    def f_inv(val):
        c = val ** 3
        return np.where(c > LAB_EPSILON, c, (val * 116.0 - 16.0) / LAB_KAPPA)
    x, y, z = WHITE_X * f_inv(fx), WHITE_Y * f_inv(fy), WHITE_Z * f_inv(fz)
    def srgb_comp(lin):
        c = np.clip(lin, 0.0, 1.0)
        return np.where(c > 0.0031308, 1.055 * (c ** (1.0 / 2.4)) - 0.055, 12.92 * c)
    r = srgb_comp(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)
    g = srgb_comp(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
    b_out = srgb_comp(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)
    return np.clip(np.stack([r, g, b_out], axis=-1), 0.0, 1.0)

def smoothstep(low, high, val):
    t = np.clip((val - low) / abs(high - low), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)

def ink_merge(src_rgb: np.ndarray, colored_raw: np.ndarray, strength: float = 0.35) -> np.ndarray:
    orig_h, orig_w = src_rgb.shape[:2]
    colored_scaled = cv2.resize(colored_raw, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
    src_f = src_rgb.astype(np.float32) / 255.0
    orig_lab = rgb_to_lab(src_f)
    col_lab = rgb_to_lab(colored_scaled)
    gray = 0.299 * src_f[..., 0] + 0.587 * src_f[..., 1] + 0.114 * src_f[..., 2]
    weight = strength * smoothstep(0.12, 0.45, gray)
    merged_L = orig_lab[..., 0] * (1.0 - weight) + col_lab[..., 0] * weight
    merged_lab = np.stack([merged_L, col_lab[..., 1], col_lab[..., 2]], axis=-1)
    merged_rgb = lab_to_rgb(merged_lab)
    return (merged_rgb * 255.0 + 0.5).clip(0, 255).astype(np.uint8)

def compute_psnr(a: np.ndarray, b: np.ndarray) -> float:
    # Resize b to a if shapes differ slightly
    if a.shape != b.shape:
        b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = np.mean((a.astype(float) / 255.0 - b.astype(float) / 255.0) ** 2)
    return float(10.0 * np.log10(1.0 / max(mse, 1e-10)))

def compute_sat(rgb_uint8: np.ndarray) -> float:
    f = rgb_uint8.astype(float) / 255.0
    return float(np.mean(np.max(f, axis=-1) - np.min(f, axis=-1)) * 100.0)

def compute_warmth(rgb_uint8: np.ndarray) -> float:
    f = rgb_uint8.astype(float) / 255.0
    return float(np.mean(f[..., 0] - f[..., 1]))


def main():
    print("=== Processing Chapter 34 with Recipe-Calibrated W8A8 Model ===", flush=True)
    t0 = time.time()

    # Load pairing
    with open(PAIRING_FILE) as f:
        pairing = json.load(f)
    pairs = sorted(pairing, key=lambda x: x["app_page"])
    print(f"Loaded {len(pairs)} page pairings", flush=True)

    # Find app images by page number
    app_files_all = [f for f in APP_DIR.glob("*.jpg") if not f.name.endswith(".json")]
    # Filenames start with page index like 0.jpg..., 1.jpg... Wait, in pairing.json app_page is 1-indexed!
    # Let's map page 1 -> 0.jpg, page 2 -> 1.jpg, or check identity json!
    app_page_map = {}
    for f in app_files_all:
        try:
            prefix = int(f.name.split(".")[0])
            app_page_map[prefix + 1] = f # 0-indexed prefix to 1-indexed page
        except Exception:
            pass

    # Load FFDNet denoiser
    weights_dir = MCV2_REPO / "denoising" / "models"
    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=str(weights_dir))
    print("FFDNet denoiser initialized.", flush=True)

    # Load LiteRT Model
    print(f"Loading LiteRT quantized model: {MODEL_PATH}", flush=True)
    interp = Interpreter(model_path=str(MODEL_PATH), num_threads=4)
    interp.allocate_tensors()
    in_idx = interp.get_input_details()[0]["index"]
    out_idx = interp.get_output_details()[0]["index"]
    print("LiteRT interpreter ready.", flush=True)

    page_results = []

    for item in pairs:
        page_num = item["app_page"]
        t_idx = item["torch_index"]

        src_path = SRC_DIR / f"src_{t_idx:02d}.jpg"
        torch_path = TORCH_DIR / f"torch_{t_idx:02d}.png"
        old_app_path = app_page_map.get(page_num)

        t_p0 = time.time()
        # 1. Load source
        src_im = Image.open(src_path).convert("RGB")
        src_rgb = np.array(src_im)
        h, w = src_rgb.shape[:2]

        # 2. FFDNet denoise
        denoised_bgr = denoiser.get_denoised_image(src_rgb, sigma=25)

        # 3. resize_pad to 576
        work_gray, pad = resize_pad(denoised_bgr, size=576)

        # 4. Canvas 576x832
        canvas = np.ones((832, 576), dtype=np.uint8) * 255
        ch = min(832, work_gray.shape[0])
        cw = min(576, work_gray.shape[1])
        canvas[:ch, :cw] = work_gray[:ch, :cw, 0]

        tensor = np.zeros((1, 5, 832, 576), dtype=np.float32)
        tensor[0, 0] = canvas.astype(np.float32) / 255.0

        # 5. Inference
        interp.set_tensor(in_idx, tensor)
        interp.invoke()
        raw_out = interp.get_tensor(out_idx)[0].copy().astype(np.float32)

        # Crop out pad
        crop_h = 832 - pad[0] if pad and pad[0] > 0 else 832
        crop_w = 576 - pad[1] if pad and pad[1] > 0 else 576
        raw_crop = raw_out[:crop_h, :crop_w]

        # 6. Ink merge
        new_merged = ink_merge(src_rgb, raw_crop, strength=0.35)

        # 7. Load reference images
        torch_rgb = np.array(Image.open(torch_path).convert("RGB"))
        old_rgb = np.array(Image.open(old_app_path).convert("RGB")) if old_app_path else None

        # 8. Compute metrics vs PyTorch Reference
        new_psnr = compute_psnr(new_merged, torch_rgb)
        old_psnr = compute_psnr(old_rgb, torch_rgb) if old_rgb is not None else item["psnr"]

        new_sat = compute_sat(new_merged)
        old_sat = compute_sat(old_rgb) if old_rgb is not None else item["app_sat"]
        ref_sat = compute_sat(torch_rgb)

        new_warmth = compute_warmth(new_merged)
        old_warmth = compute_warmth(old_rgb) if old_rgb is not None else item["app_warmth_rg"]
        ref_warmth = compute_warmth(torch_rgb)

        # 9. Save optimized JPEG images for artifact viewer
        max_dim = 1200
        scale = min(1.0, max_dim / max(h, w))
        target_w, target_h = int(w * scale), int(h * scale)

        src_view = cv2.resize(src_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA)
        new_view = cv2.resize(new_merged, (target_w, target_h), interpolation=cv2.INTER_AREA)
        old_view = cv2.resize(old_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA) if old_rgb is not None else None
        torch_view = cv2.resize(torch_rgb, (target_w, target_h), interpolation=cv2.INTER_AREA)

        p_name = f"p{page_num:02d}"
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_src.jpg"), cv2.cvtColor(src_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_new.jpg"), cv2.cvtColor(new_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        if old_view is not None:
            cv2.imwrite(str(IMAGES_DIR / f"{p_name}_old.jpg"), cv2.cvtColor(old_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_torch.jpg"), cv2.cvtColor(torch_view, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])

        delta_psnr = new_psnr - old_psnr
        page_info = {
            "page": page_num,
            "src_index": t_idx,
            "orig_size": [w, h],
            "old_psnr": round(old_psnr, 2),
            "new_psnr": round(new_psnr, 2),
            "delta_psnr": round(delta_psnr, 2),
            "old_sat": round(old_sat, 1),
            "new_sat": round(new_sat, 1),
            "ref_sat": round(ref_sat, 1),
            "old_warmth": round(old_warmth, 4),
            "new_warmth": round(new_warmth, 4),
            "ref_warmth": round(ref_warmth, 4),
            "time_s": round(time.time() - t_p0, 2),
        }
        page_results.append(page_info)
        print(f"  Page {page_num:02d}/33: New PSNR {new_psnr:.2f} dB (Old: {old_psnr:.2f} dB, Delta: {delta_psnr:+.2f} dB) | Sat: Old {old_sat:.1f} -> New {new_sat:.1f} (Ref: {ref_sat:.1f}) in {page_info['time_s']}s", flush=True)

    # Summary calculations
    new_psnrs = [p["new_psnr"] for p in page_results]
    old_psnrs = [p["old_psnr"] for p in page_results]
    delta_psnrs = [p["delta_psnr"] for p in page_results]

    summary = {
        "chapter": "Chapter 34",
        "total_pages": len(page_results),
        "total_elapsed_seconds": round(time.time() - t0, 1),
        "median_old_psnr": round(float(np.median(old_psnrs)), 2),
        "median_new_psnr": round(float(np.median(new_psnrs)), 2),
        "median_delta_psnr": round(float(np.median(delta_psnrs)), 2),
        "mean_delta_psnr": round(float(np.mean(delta_psnrs)), 2),
        "pages_improved_count": sum(1 for d in delta_psnrs if d > 0),
        "pages": page_results,
    }

    with open(ARTIFACT_DIR / "summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print("\n" + "=" * 60, flush=True)
    print(f"Batch completed in {summary['total_elapsed_seconds']}s", flush=True)
    print(f"Median Old W8A8 PSNR:          {summary['median_old_psnr']} dB", flush=True)
    print(f"Median New Recipe-Calibrated:  {summary['median_new_psnr']} dB ({summary['median_delta_psnr']:+.2f} dB gain)", flush=True)
    print(f"Pages Improved:                {summary['pages_improved_count']}/33", flush=True)
    print("=" * 60, flush=True)


if __name__ == "__main__":
    main()
