import hashlib
import os
import sys
import time
from pathlib import Path

from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.vendors.qualcomm import target as qualcomm

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

model_path = Path("/scratch/mcv2_576x832/mcv2_576x832_w8a8_rep.tflite")
out_dir = Path("/scratch/mcv2_576x832/aot_out_w8a8_rep")
out_dir.mkdir(parents=True, exist_ok=True)

t0 = time.time()
t = qualcomm.Target(soc_model=qualcomm.SocModel.SM8650)
print(f"[compile_rep] Invoking aot_compile for Qualcomm SM8650 on {model_path.name}...", flush=True)
compiled = aot_lib.aot_compile(str(model_path), output_dir=str(out_dir), target=[t], keep_going=True)
elapsed = time.time() - t0
print(f"[compile_rep] Completed in {elapsed:.1f}s", flush=True)

try:
    print(compiled.compilation_report(), flush=True)
except Exception as e:
    print(f"Report error: {e}", flush=True)

for root, _, files in sorted(os.walk(out_dir)):
    for f in sorted(files):
        p = Path(root) / f
        print(f"  {p.stat().st_size:>12}  {p.name}  (sha256: {sha256(p)})", flush=True)
