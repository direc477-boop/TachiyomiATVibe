"""AOT compile 576x832 MCV2 artifacts for Qualcomm SM8650 (HTP v75).

Isolated under 3GB container cap and 2 threads.
Runs both W8A8 and FP32 targets; logs op partitioning, duration, report and output hashes.
"""
import hashlib
import inspect
import os
import sys
import time
from pathlib import Path

from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.core import aot_types
from ai_edge_litert.aot.vendors.qualcomm import target as qualcomm

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def compile_model(model_path: Path, out_dir: Path, tag: str):
    print(f"\n==========================================", flush=True)
    print(f"[compile_{tag}] Model: {model_path} ({model_path.stat().st_size} bytes, sha256: {sha256(model_path)})", flush=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    
    t0 = time.time()
    t = qualcomm.Target(soc_model=qualcomm.SocModel.SM8650)
    print(f"[compile_{tag}] Invoking aot_compile for Qualcomm SM8650 (keep_going=True)...", flush=True)
    compiled = aot_lib.aot_compile(str(model_path), output_dir=str(out_dir), target=[t], keep_going=True)
    elapsed = time.time() - t0
    print(f"[compile_{tag}] Completed in {elapsed:.1f}s", flush=True)
    
    try:
        report = compiled.compilation_report()
        print(f"[report_{tag}] Compilation report:\n{report}", flush=True)
    except Exception as e:
        print(f"[report_{tag}] Report unavailable: {e}", flush=True)
        
    print(f"[out-dir_{tag}] Generated files in {out_dir}:", flush=True)
    for root, _, files in sorted(os.walk(out_dir)):
        for f in sorted(files):
            p = Path(root) / f
            print(f"  {p.stat().st_size:>12}  {p.name}  (sha256: {sha256(p)})", flush=True)

def main():
    root = Path("/scratch/mcv2_576x832")
    w8a8_in = root / "mcv2_576x832_w8a8.tflite"
    fp32_in = root / "mcv2_576x832_b6_int32.tflite"
    
    out_w8a8 = root / "aot_out_w8a8"
    out_fp32 = root / "aot_out_fp32"
    
    print("[main] Starting AOT compilation for SM8650...", flush=True)
    if w8a8_in.exists():
        compile_model(w8a8_in, out_w8a8, "w8a8")
    else:
        print(f"[main] WARNING: {w8a8_in} not found!", flush=True)
        
    if fp32_in.exists():
        compile_model(fp32_in, out_fp32, "fp32")
    else:
        print(f"[main] WARNING: {fp32_in} not found!", flush=True)

if __name__ == "__main__":
    main()
