"""Controlled comparison and held-out numeric evaluation for 576x832 MCV2 models.

Models evaluated:
1. ONNX FP16 Reference: manga-colorize-fp16.onnx
2. FP32 TFLite: mcv2_576x832_b6_int32.tflite
3. W8A8 Smoke TFLite: mcv2_576x832_w8a8.tflite (4-sample smoke calibration)
4. W8A8 Rep TFLite: mcv2_576x832_w8a8_rep.tflite (6-sample representative calibration)

Held-out evaluation dataset: eval_heldout_576x832.npz (6 samples, ZERO LEAKAGE)
Strictly numerical: NO visual image inspection.
Sequential model execution to maintain lean memory footprint well under 3GB cap.
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
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

ONNX_REF = Path("/scratch/mcv2_576x832/manga-colorize-fp16.onnx")
FP32_TFL = Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
W8A8_SMOKE_TFL = Path("/scratch/mcv2_576x832/mcv2_576x832_w8a8.tflite")
W8A8_REP_TFL = Path("/scratch/mcv2_576x832/mcv2_576x832_w8a8_rep.tflite")

EVAL_DATASET = Path("/scratch/mcv2_576x832/eval_heldout_576x832.npz")
MANIFEST_PATH = Path("/scratch/mcv2_576x832/datasets_manifest_576x832.json")
REPORT_OUT = Path("/scratch/mcv2_576x832/controlled_heldout_eval_report.json")


def sha256_arr(arr: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()


def psnr(ref: np.ndarray, pred: np.ndarray) -> float:
    mse = float(np.mean((ref.astype(np.float64) - pred.astype(np.float64)) ** 2))
    if mse == 0.0:
        return float("inf")
    return float(10.0 * math.log10(1.0 / mse))


def eval_onnx(path: Path, eval_tensors: list[np.ndarray]):
    print(f"\n[eval_onnx] Running {path.name} on {len(eval_tensors)} samples...", flush=True)
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    outs = []
    lats = []
    for idx, x in enumerate(eval_tensors):
        t0 = time.perf_counter()
        out_nchw = sess.run(None, {in_name: x})[0]
        lat = time.perf_counter() - t0
        out_nhwc = np.transpose(out_nchw, (0, 2, 3, 1)).astype(np.float32)
        outs.append(out_nhwc)
        lats.append(lat)
        print(f"  Sample {idx+1}: mean={out_nhwc.mean():.4f}, min={out_nhwc.min():.4f}, max={out_nhwc.max():.4f} ({lat:.2f}s)", flush=True)
    del sess
    gc.collect()
    return outs, lats


def eval_tflite(path: Path, eval_tensors: list[np.ndarray], label: str):
    print(f"\n[eval_tflite] Running {label} ({path.name}) on {len(eval_tensors)} samples...", flush=True)
    inter = Interpreter(model_path=str(path))
    inter.allocate_tensors()
    in_idx = inter.get_input_details()[0]["index"]
    out_idx = inter.get_output_details()[0]["index"]
    outs = []
    lats = []
    for idx, x in enumerate(eval_tensors):
        inter.set_tensor(in_idx, x.copy())
        t0 = time.perf_counter()
        inter.invoke()
        lat = time.perf_counter() - t0
        out = inter.get_tensor(out_idx).astype(np.float32)
        outs.append(out)
        lats.append(lat)
        print(f"  Sample {idx+1}: mean={out.mean():.4f}, min={out.min():.4f}, max={out.max():.4f} ({lat:.2f}s)", flush=True)
    del inter
    gc.collect()
    return outs, lats


def main():
    print("==================================================", flush=True)
    print("[eval] Controlled Held-Out Evaluation on 576x832 MCV2 Models", flush=True)

    manifest = json.loads(MANIFEST_PATH.read_text())
    eval_meta = manifest["held_out_evaluation_set"]
    eval_tensors = [x for x in np.load(EVAL_DATASET)["inputs"]]
    assert len(eval_meta) == len(eval_tensors)
    print(f"[eval] Loaded {len(eval_tensors)} held-out evaluation samples", flush=True)

    # 1. Run ONNX FP16 reference
    onnx_outs, onnx_lats = eval_onnx(ONNX_REF, eval_tensors)

    # 2. Run FP32 TFLite
    fp32_outs, fp32_lats = eval_tflite(FP32_TFL, eval_tensors, "fp32_tflite")

    # 3. Run W8A8 Smoke TFLite
    smoke_outs, smoke_lats = eval_tflite(W8A8_SMOKE_TFL, eval_tensors, "w8a8_smoke_tflite")

    # 4. Run W8A8 Representative TFLite
    rep_outs, rep_lats = eval_tflite(W8A8_REP_TFL, eval_tensors, "w8a8_rep_tflite")

    # Compile structured comparison results
    results = []
    print("\n==================================================", flush=True)
    print("=== SUMMARY COMPARISON ON HELD-OUT SAMPLES ===", flush=True)

    for idx, meta in enumerate(eval_meta):
        name = meta["name"]
        desc = meta["description"]

        ref = onnx_outs[idx]
        fp32 = fp32_outs[idx]
        smoke = smoke_outs[idx]
        rep = rep_outs[idx]

        for arr, tag in [(ref, "ONNX"), (fp32, "FP32"), (smoke, "Smoke"), (rep, "Rep")]:
            assert np.all(np.isfinite(arr)), f"{tag} non-finite on {name}"
            assert np.any(arr != 0.0), f"{tag} all zeros on {name}"

        # Differences vs ONNX Reference
        d_fp32_onnx_max = float(np.max(np.abs(ref - fp32)))
        d_fp32_onnx_mean = float(np.mean(np.abs(ref - fp32)))
        psnr_fp32_onnx = psnr(ref, fp32)

        d_smoke_onnx_max = float(np.max(np.abs(ref - smoke)))
        d_smoke_onnx_mean = float(np.mean(np.abs(ref - smoke)))
        psnr_smoke_onnx = psnr(ref, smoke)

        d_rep_onnx_max = float(np.max(np.abs(ref - rep)))
        d_rep_onnx_mean = float(np.mean(np.abs(ref - rep)))
        psnr_rep_onnx = psnr(ref, rep)

        # Quantization noise vs FP32 TFLite
        d_smoke_fp32_max = float(np.max(np.abs(fp32 - smoke)))
        d_smoke_fp32_mean = float(np.mean(np.abs(fp32 - smoke)))
        psnr_smoke_fp32 = psnr(fp32, smoke)

        d_rep_fp32_max = float(np.max(np.abs(fp32 - rep)))
        d_rep_fp32_mean = float(np.mean(np.abs(fp32 - rep)))
        psnr_rep_fp32 = psnr(fp32, rep)

        sample_entry = {
            "name": name,
            "description": desc,
            "input_stats": meta["stats"],
            "models": {
                "onnx_ref": {"stats": {"mean": float(ref.mean()), "std": float(ref.std()), "min": float(ref.min()), "max": float(ref.max()), "sha256": sha256_arr(ref)}, "latency_s": onnx_lats[idx]},
                "fp32_tflite": {"stats": {"mean": float(fp32.mean()), "std": float(fp32.std()), "min": float(fp32.min()), "max": float(fp32.max()), "sha256": sha256_arr(fp32)}, "latency_s": fp32_lats[idx]},
                "w8a8_smoke": {"stats": {"mean": float(smoke.mean()), "std": float(smoke.std()), "min": float(smoke.min()), "max": float(smoke.max()), "sha256": sha256_arr(smoke)}, "latency_s": smoke_lats[idx]},
                "w8a8_rep": {"stats": {"mean": float(rep.mean()), "std": float(rep.std()), "min": float(rep.min()), "max": float(rep.max()), "sha256": sha256_arr(rep)}, "latency_s": rep_lats[idx]},
            },
            "vs_onnx_ref": {
                "fp32_tflite": {"max_abs": d_fp32_onnx_max, "mean_abs": d_fp32_onnx_mean, "psnr_db": psnr_fp32_onnx},
                "w8a8_smoke": {"max_abs": d_smoke_onnx_max, "mean_abs": d_smoke_onnx_mean, "psnr_db": psnr_smoke_onnx},
                "w8a8_rep": {"max_abs": d_rep_onnx_max, "mean_abs": d_rep_onnx_mean, "psnr_db": psnr_rep_onnx},
            },
            "vs_fp32_tflite": {
                "w8a8_smoke": {"max_abs": d_smoke_fp32_max, "mean_abs": d_smoke_fp32_mean, "psnr_db": psnr_smoke_fp32},
                "w8a8_rep": {"max_abs": d_rep_fp32_max, "mean_abs": d_rep_fp32_mean, "psnr_db": psnr_rep_fp32},
            },
        }
        results.append(sample_entry)

        print(f"\n[{name}] {desc}", flush=True)
        print(f"  FP32 vs ONNX Ref:    PSNR = {psnr_fp32_onnx:>6.2f} dB | max_abs = {d_fp32_onnx_max:.4e} | mean_abs = {d_fp32_onnx_mean:.4e}", flush=True)
        print(f"  W8A8 Smoke vs Ref:   PSNR = {psnr_smoke_onnx:>6.2f} dB | max_abs = {d_smoke_onnx_max:.4e} | mean_abs = {d_smoke_onnx_mean:.4e}", flush=True)
        print(f"  W8A8 Rep vs Ref:     PSNR = {psnr_rep_onnx:>6.2f} dB | max_abs = {d_rep_onnx_max:.4e} | mean_abs = {d_rep_onnx_mean:.4e}", flush=True)
        print(f"  W8A8 Smoke vs FP32:  PSNR = {psnr_smoke_fp32:>6.2f} dB | max_abs = {d_smoke_fp32_max:.4e} | mean_abs = {d_smoke_fp32_mean:.4e}", flush=True)
        print(f"  W8A8 Rep vs FP32:    PSNR = {psnr_rep_fp32:>6.2f} dB | max_abs = {d_rep_fp32_max:.4e} | mean_abs = {d_rep_fp32_mean:.4e}", flush=True)

    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "models": {
            "onnx_ref": str(ONNX_REF),
            "fp32_tflite": str(FP32_TFL),
            "w8a8_smoke": str(W8A8_SMOKE_TFL),
            "w8a8_rep": str(W8A8_REP_TFL),
        },
        "evaluation_results": results,
    }

    REPORT_OUT.write_text(json.dumps(report, indent=2))
    print(f"\n[eval] Complete. Detailed report written to {REPORT_OUT}", flush=True)


if __name__ == "__main__":
    main()
