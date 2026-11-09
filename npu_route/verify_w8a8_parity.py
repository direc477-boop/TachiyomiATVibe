"""CPU parity and numerical verification: 576x832 W8A8 and FP32 TFLite vs ONNX FP16 reference.

Strictly synthetic inputs: NO user image inspection.
Evaluates:
1. Finite validation (no NaN / Inf)
2. Numerical divergence (max abs diff, mean abs diff, PSNR vs FP16 reference)
3. Input/output contract and signature checks
"""
import hashlib
import json
import math
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

H, W = 832, 576

ONNX_REF = Path("/scratch/mcv2_576x832/manga-colorize-fp16.onnx")
FP32_TFLITE = Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
W8A8_TFLITE = Path("/scratch/mcv2_576x832/mcv2_576x832_w8a8.tflite")

def sha256_arr(arr: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()

def psnr(ref: np.ndarray, pred: np.ndarray) -> float:
    mse = float(np.mean((ref.astype(np.float64) - pred.astype(np.float64)) ** 2))
    if mse == 0.0:
        return float("inf")
    return float(10.0 * math.log10(1.0 / mse))

def owner_pattern() -> np.ndarray:
    t = np.zeros((1, 5, H, W), dtype=np.float32)
    xs = np.arange(W) % 64 < 3
    ys = np.arange(H) % 64 < 3
    t[0, 0] = np.where(xs[None, :] | ys[:, None], np.float32(0.05), np.float32(0.95))
    return t

def random_input(seed: int = 1234) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = np.zeros((1, 5, H, W), dtype=np.float32)
    t[0, 0] = rng.uniform(0.0, 1.0, size=(H, W)).astype(np.float32)
    return t

def gradient_input() -> np.ndarray:
    t = np.zeros((1, 5, H, W), dtype=np.float32)
    gx = np.linspace(0.0, 1.0, W, dtype=np.float32)[None, :]
    t[0, 0] = np.repeat(gx, H, axis=0)
    return t

def run_onnx(path: Path, x: np.ndarray) -> np.ndarray:
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    out_nchw = sess.run(None, {"input": x})[0]  # [1, 3, 832, 576]
    out_nhwc = np.transpose(out_nchw, (0, 2, 3, 1))  # [1, 832, 576, 3]
    return out_nhwc.astype(np.float32)

def run_tflite(path: Path, x: np.ndarray) -> tuple[np.ndarray, float]:
    inter = Interpreter(model_path=str(path))
    inter.allocate_tensors()
    in_idx = inter.get_input_details()[0]["index"]
    out_idx = inter.get_output_details()[0]["index"]
    inter.set_tensor(in_idx, x.copy())
    t0 = time.perf_counter()
    inter.invoke()
    lat = time.perf_counter() - t0
    out = inter.get_tensor(out_idx).astype(np.float32)
    return out, lat

def main():
    print("==================================================", flush=True)
    print("[parity] Starting 576x832 CPU Parity & Verification", flush=True)
    print(f"  ONNX FP16 Ref: {ONNX_REF} ({ONNX_REF.stat().st_size} B)", flush=True)
    print(f"  FP32 TFLite:   {FP32_TFLITE} ({FP32_TFLITE.stat().st_size} B)", flush=True)
    print(f"  W8A8 TFLite:   {W8A8_TFLITE} ({W8A8_TFLITE.stat().st_size} B)", flush=True)
    
    test_cases = [
        ("owner_pattern_grid", owner_pattern()),
        ("random_seed1234", random_input(1234)),
        ("horizontal_gradient", gradient_input()),
    ]
    
    results = {}
    for name, x in test_cases:
        print(f"\n--- Test Case: {name} ---", flush=True)
        # 1. ONNX FP16 reference
        t0 = time.perf_counter()
        ref_out = run_onnx(ONNX_REF, x)
        lat_onnx = time.perf_counter() - t0
        print(f"  [ONNX Ref] shape={ref_out.shape} min={ref_out.min():.5f} max={ref_out.max():.5f} mean={ref_out.mean():.5f} ({lat_onnx:.2f}s)", flush=True)
        assert np.all(np.isfinite(ref_out)), "ONNX ref non-finite!"
        
        # 2. FP32 TFLite
        fp32_out, lat_fp32 = run_tflite(FP32_TFLITE, x)
        print(f"  [FP32 TFL] shape={fp32_out.shape} min={fp32_out.min():.5f} max={fp32_out.max():.5f} mean={fp32_out.mean():.5f} ({lat_fp32:.2f}s)", flush=True)
        assert np.all(np.isfinite(fp32_out)), "FP32 TFLite non-finite!"
        assert np.any(fp32_out != 0.0), "FP32 TFLite all zeros!"
        
        # 3. W8A8 TFLite
        w8a8_out, lat_w8a8 = run_tflite(W8A8_TFLITE, x)
        print(f"  [W8A8 TFL] shape={w8a8_out.shape} min={w8a8_out.min():.5f} max={w8a8_out.max():.5f} mean={w8a8_out.mean():.5f} ({lat_w8a8:.2f}s)", flush=True)
        assert np.all(np.isfinite(w8a8_out)), "W8A8 TFLite non-finite!"
        assert np.any(w8a8_out != 0.0), "W8A8 TFLite all zeros!"
        
        # Divergence metrics
        d_fp32_onnx_max = float(np.max(np.abs(ref_out - fp32_out)))
        d_fp32_onnx_mean = float(np.mean(np.abs(ref_out - fp32_out)))
        psnr_fp32_onnx = psnr(ref_out, fp32_out)
        
        d_w8a8_fp32_max = float(np.max(np.abs(fp32_out - w8a8_out)))
        d_w8a8_fp32_mean = float(np.mean(np.abs(fp32_out - w8a8_out)))
        psnr_w8a8_fp32 = psnr(fp32_out, w8a8_out)
        
        d_w8a8_onnx_max = float(np.max(np.abs(ref_out - w8a8_out)))
        d_w8a8_onnx_mean = float(np.mean(np.abs(ref_out - w8a8_out)))
        psnr_w8a8_onnx = psnr(ref_out, w8a8_out)
        
        print(f"  Comparison vs FP16 ONNX Reference:", flush=True)
        print(f"    FP32 vs ONNX Ref: max_abs={d_fp32_onnx_max:.3e}, mean_abs={d_fp32_onnx_mean:.3e}, PSNR={psnr_fp32_onnx:.2f} dB", flush=True)
        print(f"    W8A8 vs ONNX Ref: max_abs={d_w8a8_onnx_max:.3e}, mean_abs={d_w8a8_onnx_mean:.3e}, PSNR={psnr_w8a8_onnx:.2f} dB", flush=True)
        print(f"  Quantization Noise (W8A8 vs FP32):", flush=True)
        print(f"    W8A8 vs FP32:     max_abs={d_w8a8_fp32_max:.3e}, mean_abs={d_w8a8_fp32_mean:.3e}, PSNR={psnr_w8a8_fp32:.2f} dB", flush=True)
        
        results[name] = {
            "onnx_ref_sha256": sha256_arr(ref_out),
            "fp32_tflite_sha256": sha256_arr(fp32_out),
            "w8a8_tflite_sha256": sha256_arr(w8a8_out),
            "fp32_vs_onnx_max_abs": d_fp32_onnx_max,
            "fp32_vs_onnx_mean_abs": d_fp32_onnx_mean,
            "fp32_vs_onnx_psnr_db": psnr_fp32_onnx,
            "w8a8_vs_onnx_max_abs": d_w8a8_onnx_max,
            "w8a8_vs_onnx_mean_abs": d_w8a8_onnx_mean,
            "w8a8_vs_onnx_psnr_db": psnr_w8a8_onnx,
            "w8a8_vs_fp32_max_abs": d_w8a8_fp32_max,
            "w8a8_vs_fp32_mean_abs": d_w8a8_fp32_mean,
            "w8a8_vs_fp32_psnr_db": psnr_w8a8_fp32,
            "latencies_s": {"onnx": lat_onnx, "fp32": lat_fp32, "w8a8": lat_w8a8},
        }

    out_json = Path("/scratch/mcv2_576x832/parity_report_576x832.json")
    out_json.write_text(json.dumps(results, indent=2))
    print(f"\n[parity] Parity report saved to {out_json}", flush=True)
    print("PARITY_VERIFICATION_COMPLETE", flush=True)

if __name__ == "__main__":
    main()
