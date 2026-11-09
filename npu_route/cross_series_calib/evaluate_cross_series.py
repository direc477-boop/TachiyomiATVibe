import hashlib
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

ROOT = Path("/scratch/mcv2_576x832")
CROSS = ROOT / "cross_series"
OUT_REPORT = CROSS / "cross_series_eval_report.json"

ONNX_PATH = ROOT / "manga-colorize-fp16.onnx"
FP32_PATH = ROOT / "mcv2_576x832_b6_int32.tflite"

CANDIDATES = {
    "mixed_prev_baseline": ROOT / "rawkuma_experiment" / "mcv2_w8a8_mixed.tflite",
    "synthetic_w8a8": ROOT / "mcv2_576x832_w8a8.tflite",
    "rep_earlier_w8a8": ROOT / "mcv2_576x832_w8a8_rep.tflite",
    "cross_mixed_w8a8": CROSS / "mcv2_576x832_w8a8_cross_mixed.tflite",
    "cross_manga_w8a8": CROSS / "mcv2_576x832_w8a8_cross_manga.tflite",
}

EVAL_NPZ = CROSS / "held_out_evaluation.npz"
SASA_NPZ = CROSS / "sasa_evaluation.npz"
MANIFEST_PATH = CROSS / "dataset_split.json"

def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def ensure_4d(tensors):
    out = []
    for t in tensors:
        if t.ndim == 3:
            out.append(np.expand_dims(t, 0))
        elif t.ndim == 4:
            out.append(t)
        else:
            raise ValueError(f"Unexpected tensor shape {t.shape}")
    return out

def run_tflite(path: Path, tensors):
    tensors_4d = ensure_4d(tensors)
    inter = Interpreter(model_path=str(path), num_threads=2)
    inter.allocate_tensors()
    in_idx = inter.get_input_details()[0]["index"]
    out_idx = inter.get_output_details()[0]["index"]
    outs = []
    t0 = time.time()
    for arr in tensors_4d:
        inter.set_tensor(in_idx, arr)
        inter.invoke()
        outs.append(inter.get_tensor(out_idx).copy().astype(np.float32))
    elapsed = time.time() - t0
    return outs, elapsed

def run_onnx(path: Path, tensors):
    tensors_4d = ensure_4d(tensors)
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    outs = []
    t0 = time.time()
    for arr in tensors_4d:
        out_nchw = sess.run(None, {in_name: arr})[0]
        out_nhwc = np.transpose(out_nchw, (0, 2, 3, 1)).astype(np.float32)
        outs.append(out_nhwc)
    elapsed = time.time() - t0
    return outs, elapsed

def evaluate_pages(preds: list[np.ndarray], refs: list[np.ndarray], meta: list[dict]):
    pages = []
    for i, (pred, ref) in enumerate(zip(preds, refs)):
        delta = pred.astype(np.float64) - ref.astype(np.float64)
        mse = float(np.mean(delta ** 2))
        psnr = -10.0 * math.log10(mse) if mse > 0 else float("inf")
        mae = float(np.mean(np.abs(delta)))
        max_abs = float(np.max(np.abs(delta)))
        signed_rgb = np.mean(delta, axis=(0, 1, 2)).tolist()
        
        info = {
            "index": i,
            "psnr_db": psnr,
            "mae": mae,
            "max_abs": max_abs,
            "signed_rgb_shift": {
                "r": signed_rgb[0],
                "g": signed_rgb[1],
                "b": signed_rgb[2],
            }
        }
        if meta and i < len(meta):
            info["rel_file"] = meta[i].get("rel_file", "")
            info["series"] = meta[i].get("series", "")
            info["is_previous_heldout"] = meta[i].get("is_previous_heldout", False)
            info["gate_score"] = meta[i].get("gate_score", 0.0)
        pages.append(info)
    return pages

def main():
    print("==================================================", flush=True)
    print("[cross_eval] Starting cross-series held-out evaluation", flush=True)

    heldout_tensors = np.load(EVAL_NPZ)["inputs"]
    sasa_raw = np.load(SASA_NPZ)["inputs"]
    sasa_tensors = [sasa_raw]
    print(f"[cross_eval] Loaded {len(heldout_tensors)} held-out pages + 1 sasa page", flush=True)

    manifest = json.loads(MANIFEST_PATH.read_text())
    heldout_meta = [x for x in manifest["split"] if x["group"] == "held_out"]
    assert len(heldout_meta) == len(heldout_tensors)

    # 1. Run References
    print("\n[cross_eval] Running FP32 TFLite reference...", flush=True)
    fp32_heldout, fp32_lat = run_tflite(FP32_PATH, heldout_tensors)
    fp32_sasa, _ = run_tflite(FP32_PATH, sasa_tensors)

    print("\n[cross_eval] Running FP16 ONNX reference...", flush=True)
    onnx_heldout, onnx_lat = run_onnx(ONNX_PATH, heldout_tensors)
    onnx_sasa, _ = run_onnx(ONNX_PATH, sasa_tensors)

    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "references": {
            "fp32_tflite": {"path": str(FP32_PATH), "sha256": sha256_file(FP32_PATH), "latency_s": fp32_lat},
            "fp16_onnx": {"path": str(ONNX_PATH), "sha256": sha256_file(ONNX_PATH), "latency_s": onnx_lat},
        },
        "models": {},
    }

    # Evaluate Candidate Models
    for tag, model_path in CANDIDATES.items():
        if not model_path.exists():
            print(f"\n[cross_eval] Skipping {tag} (not found: {model_path})", flush=True)
            continue
        print(f"\n[cross_eval] Evaluating {tag} ({model_path.name})...", flush=True)
        m_sha256 = sha256_file(model_path)
        m_heldout, m_lat = run_tflite(model_path, heldout_tensors)
        m_sasa, _ = run_tflite(model_path, sasa_tensors)

        # vs FP32 Graph Reference
        eval_vs_fp32 = evaluate_pages(m_heldout, fp32_heldout, heldout_meta)
        sasa_vs_fp32 = evaluate_pages(m_sasa, fp32_sasa, [{"rel_file": "sasa.png", "series": "held_out_user"}])[0]

        # vs FP16 ONNX Reference
        eval_vs_onnx = evaluate_pages(m_heldout, onnx_heldout, heldout_meta)
        sasa_vs_onnx = evaluate_pages(m_sasa, onnx_sasa, [{"rel_file": "sasa.png", "series": "held_out_user"}])[0]

        # Breakdowns across series
        series_breakdown = {}
        prev_7_psnr = []
        for p in eval_vs_fp32:
            s = p["series"]
            series_breakdown.setdefault(s, []).append(p["psnr_db"])
            if p.get("is_previous_heldout"):
                prev_7_psnr.append(p["psnr_db"])

        mean_psnr_all = float(np.mean([p["psnr_db"] for p in eval_vs_fp32]))
        mean_psnr_prev7 = float(np.mean(prev_7_psnr)) if prev_7_psnr else None
        mean_rgb_shift = [
            float(np.mean([p["signed_rgb_shift"]["r"] for p in eval_vs_fp32])),
            float(np.mean([p["signed_rgb_shift"]["g"] for p in eval_vs_fp32])),
            float(np.mean([p["signed_rgb_shift"]["b"] for p in eval_vs_fp32])),
        ]

        # vs ONNX summaries
        mean_psnr_onnx_all = float(np.mean([p["psnr_db"] for p in eval_vs_onnx]))
        prev_7_onnx_psnr = [p["psnr_db"] for p in eval_vs_onnx if p.get("is_previous_heldout")]
        mean_psnr_onnx_prev7 = float(np.mean(prev_7_onnx_psnr)) if prev_7_onnx_psnr else None

        print(f"  [{tag}] All 20 pages Mean PSNR vs FP32: {mean_psnr_all:.2f} dB (vs ONNX: {mean_psnr_onnx_all:.2f} dB)", flush=True)
        if mean_psnr_prev7:
            print(f"  [{tag}] Previous 7 drawing-saikyou pages Mean PSNR vs FP32: {mean_psnr_prev7:.2f} dB", flush=True)
        print(f"  [{tag}] sasa.png PSNR vs FP32: {sasa_vs_fp32['psnr_db']:.2f} dB (vs ONNX: {sasa_vs_onnx['psnr_db']:.2f} dB)", flush=True)
        print(f"  [{tag}] Mean signed RGB shift (vs FP32): R={mean_rgb_shift[0]:+.5f}, G={mean_rgb_shift[1]:+.5f}, B={mean_rgb_shift[2]:+.5f}", flush=True)

        report["models"][tag] = {
            "model_path": str(model_path),
            "sha256": m_sha256,
            "size_bytes": model_path.stat().st_size,
            "latency_total_s": m_lat,
            "summary": {
                "mean_psnr_vs_fp32_all_20": mean_psnr_all,
                "mean_psnr_vs_fp32_prev_7": mean_psnr_prev7,
                "mean_psnr_vs_onnx_all_20": mean_psnr_onnx_all,
                "mean_psnr_vs_onnx_prev_7": mean_psnr_onnx_prev7,
                "sasa_psnr_vs_fp32": sasa_vs_fp32["psnr_db"],
                "sasa_psnr_vs_onnx": sasa_vs_onnx["psnr_db"],
                "mean_signed_rgb_shift_all_20": {
                    "r": mean_rgb_shift[0],
                    "g": mean_rgb_shift[1],
                    "b": mean_rgb_shift[2],
                },
                "series_mean_psnr_vs_fp32": {s: float(np.mean(vals)) for s, vals in series_breakdown.items()},
            },
            "held_out_pages_vs_fp32": eval_vs_fp32,
            "held_out_pages_vs_onnx": eval_vs_onnx,
            "sasa_vs_fp32": sasa_vs_fp32,
            "sasa_vs_onnx": sasa_vs_onnx,
        }

    OUT_REPORT.write_text(json.dumps(report, indent=2))
    print(f"\n[cross_eval] Full evaluation report written to {OUT_REPORT}", flush=True)

if __name__ == "__main__":
    main()
