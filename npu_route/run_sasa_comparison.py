"""Head-to-head comparison on user image sasa.png (1080x1535).
576x832 MCV2 models evaluated on Host CPU:
1. FP16 ONNX Reference (CPUExecutionProvider)
2. FP32 TFLite (mcv2_576x832_b6_int32.tflite)
3. W8A8 Representative TFLite (mcv2_576x832_w8a8_rep.tflite)
4. W8A8 Smoke TFLite (mcv2_576x832_w8a8.tflite)

Pipeline:
- Letterbox fit 1080x1535 -> 576x832 (content 576x819, pad top=6, bottom=7, left=0, right=0, white 1.0f)
- Model inference (sequential under 3GB cap, gc.collect)
- Crop content rect [6:825, 0:576]
- Ink-aware 0.35 merge (CpuChromaCombiner kornia sRGB<->Lab D65, smoothstep [0.12, 0.45], 1.5MP cap 1027x1460 -> scale back 1080x1535)
- Quantitative metrics & SHA256 hashes
- Programmatic only: NO visual inspection.
"""
import gc
import hashlib
import json
import math
import os
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

ROOT = Path("/scratch/mcv2_576x832")
SRC_IMG_PATH = ROOT / "sasa.png"
OUT_DIR = ROOT / "sasa_outputs"

ONNX_REF_PATH = ROOT / "manga-colorize-fp16.onnx"
FP32_TFL_PATH = ROOT / "mcv2_576x832_b6_int32.tflite"
W8A8_REP_TFL_PATH = ROOT / "mcv2_576x832_w8a8_rep.tflite"
W8A8_SMOKE_TFL_PATH = ROOT / "mcv2_576x832_w8a8.tflite"

TARGET_W = 576
TARGET_H = 832
CHANNELS = 5
MAX_MERGE_PIXELS = 1_500_000

# Color conversion constants (kornia sRGB <-> Lab D65, matching LineArtMerger.kt)
WHITE_X = 0.95047
WHITE_Y = 1.0
WHITE_Z = 1.08883
LAB_KAPPA = 903.3
LAB_EPSILON = 0.008856

SRGB_THRESHOLD = 0.04045
SRGB_LINEAR_THRESHOLD = 0.0031308

def srgb_linearize(c: np.ndarray) -> np.ndarray:
    c = np.clip(c, 0.0, 1.0)
    return np.where(c > SRGB_THRESHOLD, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)

def srgb_to_xyz(rgb: np.ndarray):
    lr = srgb_linearize(rgb[..., 0])
    lg = srgb_linearize(rgb[..., 1])
    lb = srgb_linearize(rgb[..., 2])
    x = 0.412453 * lr + 0.357580 * lg + 0.180423 * lb
    y = 0.212671 * lr + 0.715160 * lg + 0.072169 * lb
    z = 0.019334 * lr + 0.119193 * lg + 0.950227 * lb
    return x, y, z

def f_ratio(val: np.ndarray) -> np.ndarray:
    return np.where(val > LAB_EPSILON, np.cbrt(val), (LAB_KAPPA * val + 16.0) / 116.0)

def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    x, y, z = srgb_to_xyz(rgb)
    fx = f_ratio(x / WHITE_X)
    fy = f_ratio(y / WHITE_Y)
    fz = f_ratio(z / WHITE_Z)
    L = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return np.stack([L, a, b], axis=-1)

def f_inv_ratio(val: np.ndarray) -> np.ndarray:
    cube = val ** 3
    return np.where(cube > LAB_EPSILON, cube, (val * 116.0 - 16.0) / LAB_KAPPA)

def xyz_to_srgb_component(linear: np.ndarray) -> np.ndarray:
    c = np.clip(linear, 0.0, 1.0)
    return np.where(c > SRGB_LINEAR_THRESHOLD, 1.055 * (c ** (1.0 / 2.4)) - 0.055, 12.92 * c)

def lab_to_rgb(lab: np.ndarray) -> np.ndarray:
    L = lab[..., 0]
    a = lab[..., 1]
    b = lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0
    x = WHITE_X * f_inv_ratio(fx)
    y = WHITE_Y * f_inv_ratio(fy)
    z = WHITE_Z * f_inv_ratio(fz)
    r = xyz_to_srgb_component(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)
    g = xyz_to_srgb_component(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
    b_out = xyz_to_srgb_component(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)
    return np.clip(np.stack([r, g, b_out], axis=-1), 0.0, 1.0)

def smoothstep(edge_low: float, edge_high: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - edge_low) / abs(edge_high - edge_low), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)

def cpu_chroma_combiner_merge(orig_rgb: np.ndarray, colored_rgb: np.ndarray, strength: float = 0.35) -> np.ndarray:
    orig_lab = rgb_to_lab(orig_rgb)
    col_lab = rgb_to_lab(colored_rgb)
    gray = 0.299 * orig_rgb[..., 0] + 0.587 * orig_rgb[..., 1] + 0.114 * orig_rgb[..., 2]
    weight = strength * smoothstep(0.12, 0.45, gray)
    merged_L = orig_lab[..., 0] * (1.0 - weight) + col_lab[..., 0] * weight
    merged_lab = np.stack([merged_L, col_lab[..., 1], col_lab[..., 2]], axis=-1)
    return lab_to_rgb(merged_lab)

def sha256_arr(arr: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()

def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def psnr(ref: np.ndarray, pred: np.ndarray) -> float:
    mse = float(np.mean((ref.astype(np.float64) - pred.astype(np.float64)) ** 2))
    if mse == 0.0:
        return float("inf")
    return float(10.0 * math.log10(1.0 / mse))

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print("==================================================", flush=True)
    print(f"[sasa_comp] Starting comparison on {SRC_IMG_PATH}", flush=True)
    
    # 1. Load source image metadata & compute letterbox geometry
    src_pil = Image.open(SRC_IMG_PATH).convert("RGB")
    src_w, src_h = src_pil.size
    print(f"[sasa_comp] Source image size: {src_w}x{src_h}, pixels: {src_w * src_h}", flush=True)
    
    scale = min(TARGET_W / float(src_w), TARGET_H / float(src_h))
    content_w = int(round(src_w * scale))
    content_h = int(round(src_h * scale))
    pad_left = (TARGET_W - content_w) // 2
    pad_top = (TARGET_H - content_h) // 2
    pad_right = TARGET_W - content_w - pad_left
    pad_bottom = TARGET_H - content_h - pad_top
    
    print(f"[sasa_comp] Letterbox scale: {scale:.6f}", flush=True)
    print(f"[sasa_comp] Content rect: {content_w}x{content_h} at (left={pad_left}, top={pad_top})", flush=True)
    print(f"[sasa_comp] Pad: left={pad_left}, top={pad_top}, right={pad_right}, bottom={pad_bottom}", flush=True)
    
    # 2. Preprocess to letterbox plane: grayscale content, paper-white (1.0) padding
    src_gray = src_pil.convert("L")
    gray_scaled = src_gray.resize((content_w, content_h), Image.Resampling.BILINEAR)
    gray_scaled_arr = np.asarray(gray_scaled, dtype=np.float32) / 255.0
    
    input_plane = np.ones((TARGET_H, TARGET_W), dtype=np.float32)
    input_plane[pad_top : pad_top + content_h, pad_left : pad_left + content_w] = gray_scaled_arr
    
    # 5-channel NCHW input tensor
    inp_tensor = np.zeros((1, CHANNELS, TARGET_H, TARGET_W), dtype=np.float32)
    inp_tensor[0, 0] = input_plane
    print(f"[sasa_comp] Input tensor shape: {inp_tensor.shape}, sha256: {sha256_arr(inp_tensor)}", flush=True)
    print(f"[sasa_comp] Input stats: min={inp_tensor.min():.4f}, max={inp_tensor.max():.4f}, mean={inp_tensor.mean():.4f}, std={inp_tensor.std():.4f}", flush=True)

    # 3. Sequential Inference
    model_configs = [
        ("onnx_fp16", ONNX_REF_PATH, "onnx"),
        ("tflite_fp32", FP32_TFL_PATH, "tflite"),
        ("tflite_w8a8_rep", W8A8_REP_TFL_PATH, "tflite"),
        ("tflite_w8a8_smoke", W8A8_SMOKE_TFL_PATH, "tflite"),
    ]
    
    raw_cropped_outputs = {}
    latencies = {}
    
    for name, path, mtype in model_configs:
        print(f"\n[sasa_comp] Running {name} ({path.name})...", flush=True)
        t0 = time.perf_counter()
        if mtype == "onnx":
            sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            in_name = sess.get_inputs()[0].name
            out_nchw = sess.run(None, {in_name: inp_tensor})[0]
            lat = time.perf_counter() - t0
            out_nhwc = np.transpose(out_nchw, (0, 2, 3, 1)).astype(np.float32)
            del sess
            gc.collect()
        else:
            inter = Interpreter(model_path=str(path))
            inter.allocate_tensors()
            in_idx = inter.get_input_details()[0]["index"]
            out_idx = inter.get_output_details()[0]["index"]
            inter.set_tensor(in_idx, inp_tensor.copy())
            inter.invoke()
            lat = time.perf_counter() - t0
            out_nhwc = inter.get_tensor(out_idx).astype(np.float32)
            del inter
            gc.collect()
            
        latencies[name] = lat
        # Crop content rect
        cropped = out_nhwc[0, pad_top : pad_top + content_h, pad_left : pad_left + content_w, :]
        cropped = np.clip(cropped, 0.0, 1.0)
        raw_cropped_outputs[name] = cropped
        print(f"  Done in {lat:.2f}s, cropped shape: {cropped.shape}, mean={cropped.mean():.4f}, std={cropped.std():.4f}, min={cropped.min():.4f}, max={cropped.max():.4f}", flush=True)

    # 4. Ink-Aware 0.35 Merge (CpuChromaCombiner Twin)
    # Check 1.5 MP cap
    pixels = src_w * src_h
    if pixels > MAX_MERGE_PIXELS:
        shrink = math.sqrt(MAX_MERGE_PIXELS / float(pixels))
        merge_w = max(1, int(round(src_w * shrink)))
        merge_h = max(1, int(round(src_h * shrink)))
    else:
        merge_w, merge_h = src_w, src_h
    print(f"\n[sasa_comp] Ink-aware merge dimensions: {merge_w}x{merge_h} (shrink={shrink:.6f} from {src_w}x{src_h})", flush=True)

    src_scaled_merge = src_pil.resize((merge_w, merge_h), Image.Resampling.BILINEAR)
    src_scaled_merge_arr = np.asarray(src_scaled_merge, dtype=np.float32) / 255.0

    final_merged_outputs = {}
    saved_files = {}

    for name in raw_cropped_outputs:
        cropped_col = raw_cropped_outputs[name]
        cropped_pil = Image.fromarray((cropped_col * 255.0 + 0.5).astype(np.uint8), "RGB")
        
        # Save raw cropped output
        raw_png_name = f"sasa_{name}_raw_cropped.png"
        raw_png_path = OUT_DIR / raw_png_name
        cropped_pil.save(raw_png_path)
        
        # Merge at (merge_w, merge_h)
        col_scaled_merge = cropped_pil.resize((merge_w, merge_h), Image.Resampling.BILINEAR)
        col_scaled_merge_arr = np.asarray(col_scaled_merge, dtype=np.float32) / 255.0
        
        merged_merge_dim = cpu_chroma_combiner_merge(src_scaled_merge_arr, col_scaled_merge_arr, strength=0.35)
        merged_merge_pil = Image.fromarray(np.clip(merged_merge_dim * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGB")
        
        # Scale back to source dimensions (1080x1535)
        final_pil = merged_merge_pil.resize((src_w, src_h), Image.Resampling.BILINEAR)
        final_arr = np.asarray(final_pil, dtype=np.float32) / 255.0
        final_merged_outputs[name] = final_arr
        
        final_png_name = f"sasa_{name}_final_merged.png"
        final_png_path = OUT_DIR / final_png_name
        final_pil.save(final_png_path)
        
        saved_files[name] = {
            "raw_cropped_png": str(raw_png_path),
            "raw_cropped_sha256": sha256_file(raw_png_path),
            "final_merged_png": str(final_png_path),
            "final_merged_sha256": sha256_file(final_png_path),
        }
        print(f"[sasa_comp] Saved {final_png_name} (sha256: {saved_files[name]['final_merged_sha256']})", flush=True)

    # 5. Compute Comparative Numeric Metrics
    ref_raw = raw_cropped_outputs["onnx_fp16"]
    ref_final = final_merged_outputs["onnx_fp16"]
    
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source_image": {
            "path": str(SRC_IMG_PATH),
            "width": src_w,
            "height": src_h,
            "pixels": pixels,
            "sha256": sha256_file(SRC_IMG_PATH),
        },
        "letterbox": {
            "target_w": TARGET_W,
            "target_h": TARGET_H,
            "scale": scale,
            "content_w": content_w,
            "content_h": content_h,
            "pad_left": pad_left,
            "pad_top": pad_top,
            "pad_right": pad_right,
            "pad_bottom": pad_bottom,
        },
        "merge_parameters": {
            "max_pixels": MAX_MERGE_PIXELS,
            "merge_w": merge_w,
            "merge_h": merge_h,
            "shading_strength": 0.35,
            "smoothstep_edges": [0.12, 0.45],
            "algorithm": "CpuChromaCombiner (kornia sRGB<->Lab D65)",
        },
        "models_evaluated": {},
    }
    
    for name, path, _ in model_configs:
        raw = raw_cropped_outputs[name]
        final = final_merged_outputs[name]
        
        raw_psnr = psnr(ref_raw, raw)
        raw_mae = float(np.mean(np.abs(ref_raw.astype(np.float64) - raw.astype(np.float64))))
        raw_max_abs = float(np.max(np.abs(ref_raw.astype(np.float64) - raw.astype(np.float64))))
        
        final_psnr = psnr(ref_final, final)
        final_mae = float(np.mean(np.abs(ref_final.astype(np.float64) - final.astype(np.float64))))
        final_max_abs = float(np.max(np.abs(ref_final.astype(np.float64) - final.astype(np.float64))))
        
        report["models_evaluated"][name] = {
            "model_path": str(path),
            "model_sha256": sha256_file(path),
            "latency_seconds": latencies[name],
            "execution_target": "Host CPU (XNNPACK / CPUExecutionProvider) - NOT NPU",
            "files": saved_files[name],
            "raw_cropped_stats": {
                "shape": list(raw.shape),
                "min": float(raw.min()),
                "max": float(raw.max()),
                "mean": float(raw.mean()),
                "std": float(raw.std()),
                "tensor_sha256": sha256_arr(raw),
                "psnr_vs_onnx_ref": raw_psnr,
                "mae_vs_onnx_ref": raw_mae,
                "max_abs_vs_onnx_ref": raw_max_abs,
            },
            "final_merged_stats": {
                "shape": list(final.shape),
                "min": float(final.min()),
                "max": float(final.max()),
                "mean": float(final.mean()),
                "std": float(final.std()),
                "tensor_sha256": sha256_arr(final),
                "psnr_vs_onnx_ref": final_psnr,
                "mae_vs_onnx_ref": final_mae,
                "max_abs_vs_onnx_ref": final_max_abs,
            },
        }

    report_path = OUT_DIR / "sasa_comparison_report.json"
    report_path.write_text(json.dumps(report, indent=2))
    print(f"\n[sasa_comp] Comparison report written to {report_path}", flush=True)

if __name__ == "__main__":
    main()
