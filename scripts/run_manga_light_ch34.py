#!/usr/bin/env python3
"""Run Chapter 34 through original Manga Light ONNX pipeline (v6_sam_encoder + v6_generator)."""

import json
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

REPO_ROOT = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE")
MODELS_DIR = REPO_ROOT / "models_local" / "manga-light-original"
SAM_MODEL = MODELS_DIR / "v6_sam_encoder.onnx"
GEN_MODEL = MODELS_DIR / "v6_generator.onnx"

CH34_DIR = Path(r"C:\Users\ADMIN\Downloads\ch34_compare")
SRC_DIR = CH34_DIR / "sources"
TORCH_DIR = CH34_DIR / "torch_out"
OUT_DIR = CH34_DIR / "manga_light_out"
OUT_DIR.mkdir(parents=True, exist_ok=True)
PAIRING_FILE = CH34_DIR / "pairing.json"

def compute_psnr(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = np.mean((a.astype(float) / 255.0 - b.astype(float) / 255.0) ** 2)
    return float(10.0 * np.log10(1.0 / max(mse, 1e-10)))

def compute_sat(rgb_uint8: np.ndarray) -> float:
    hsv = cv2.cvtColor(rgb_uint8, cv2.COLOR_RGB2HSV)
    return float(hsv[..., 1].mean())

def main():
    print("=== Running Chapter 34 through Original Manga Light ONNX ===", flush=True)
    t0 = time.time()

    with open(PAIRING_FILE) as f:
        pairing = json.load(f)
    pairs = sorted(pairing, key=lambda x: x["app_page"])

    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    print("Loading ONNX sessions...", flush=True)
    sam = ort.InferenceSession(str(SAM_MODEL), so, providers=["CPUExecutionProvider"])
    gen = ort.InferenceSession(str(GEN_MODEL), so, providers=["CPUExecutionProvider"])
    print("Sessions loaded.", flush=True)

    results = []

    for item in pairs:
        page_num = item["app_page"]
        t_idx = item["torch_index"]

        src_path = SRC_DIR / f"src_{t_idx:02d}.jpg"
        torch_path = TORCH_DIR / f"torch_{t_idx:02d}.png"
        out_path = OUT_DIR / f"ml_{page_num:02d}.jpg"

        t_p0 = time.time()
        # 1. Load source grayscale
        bgr = cv2.imread(str(src_path))
        orig_h, orig_w = bgr.shape[:2]
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)

        # 2. Prepare 512x512 luma and 1024x1024 SAM input
        im512 = cv2.resize(gray, (512, 512), interpolation=cv2.INTER_AREA)
        l_01 = im512.astype(np.float32) / 255.0
        l_1024 = cv2.resize(l_01, (1024, 1024), interpolation=cv2.INTER_LINEAR)
        rgb_sam = np.stack([l_1024, l_1024, l_1024], axis=0)[np.newaxis].astype(np.float32)

        # 3. Run SAM encoder
        sam_out = sam.run(None, {"rgb_input": rgb_sam})
        sam_l0 = sam_out[0]
        sam_l1 = sam_out[1]

        # 4. Run Generator
        l_norm = (im512.astype(np.float32) / 127.5) - 1.0
        l_tensor = l_norm[np.newaxis, np.newaxis, :, :]
        gen_out = gen.run(None, {
            "L_bw": l_tensor,
            "sam_level0": sam_l0,
            "sam_level1": sam_l1,
            "wd14_embedding": np.zeros((1, 1024), dtype=np.float32),
        })

        # 5. Denormalize to uint8 RGB
        rgb_512 = ((gen_out[0][0].transpose(1, 2, 0) + 1.0) * 127.5).clip(0, 255).astype(np.uint8)
        # Resize to original resolution
        rgb_full = cv2.resize(rgb_512, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)

        # Save output image
        cv2.imwrite(str(out_path), cv2.cvtColor(rgb_full, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])

        # Compute PSNR vs PyTorch Reference
        torch_rgb = np.array(Image.open(torch_path).convert("RGB"))
        psnr = compute_psnr(rgb_full, torch_rgb)
        sat = compute_sat(rgb_full)

        res = {
            "page": page_num,
            "torch_index": t_idx,
            "ml_psnr_vs_torch": round(psnr, 2),
            "ml_sat": round(sat, 1),
            "ref_sat": round(compute_sat(torch_rgb), 1),
            "time_s": round(time.time() - t_p0, 2),
        }
        results.append(res)
        print(f"  Page {page_num:02d}/33: PSNR vs PyTorch = {psnr:.2f} dB, Sat = {sat:.1f} in {res['time_s']}s", flush=True)

    summary = {
        "chapter": "Chapter 34",
        "total_pages": len(results),
        "median_psnr": round(float(np.median([r["ml_psnr_vs_torch"] for r in results])), 2),
        "mean_psnr": round(float(np.mean([r["ml_psnr_vs_torch"] for r in results])), 2),
        "median_sat": round(float(np.median([r["ml_sat"] for r in results])), 1),
        "total_elapsed_s": round(time.time() - t0, 1),
        "pages": results,
    }

    with open(CH34_DIR / "manga_light_ch34_summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print("\n" + "=" * 60, flush=True)
    print(f"Manga Light Chapter 34 done in {summary['total_elapsed_s']}s", flush=True)
    print(f"Median PSNR vs PyTorch: {summary['median_psnr']} dB", flush=True)
    print(f"Mean PSNR vs PyTorch:   {summary['mean_psnr']} dB", flush=True)
    print(f"Median Saturation:      {summary['median_sat']}", flush=True)
    print("=" * 60, flush=True)

if __name__ == "__main__":
    main()
