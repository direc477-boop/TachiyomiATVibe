"""AOT-compile the preserved B6-v1 FP32 artifact for SM8650 (HTP v75).

Isolated-scratch contract: reads /scratch/in/, writes /scratch/out/ + logs.
keep_going=True so per-op failures are attributed instead of aborting.
"""
import inspect
import os
import sys
import time

from ai_edge_litert.aot import aot_compile as aot_lib
from ai_edge_litert.aot.core import aot_types
from ai_edge_litert.aot.vendors.qualcomm import target as qualcomm

MODEL = sys.argv[1] if len(sys.argv) > 1 else "/scratch/in/mcv2-fp32-b6-int32idx_float32.tflite"
OUT = sys.argv[2] if len(sys.argv) > 2 else "/scratch/out"

t0 = time.time()
print(f"[compile] model={MODEL} out={OUT}", flush=True)
try:
    print("[compile] CompilationConfig sig:", inspect.signature(aot_types.CompilationConfig), flush=True)
except Exception as e:  # noqa: BLE001
    print("[compile] CompilationConfig introspect failed:", e, flush=True)

t = qualcomm.Target(soc_model=qualcomm.SocModel.SM8650)
compiled = aot_lib.aot_compile(MODEL, output_dir=OUT, target=[t], keep_going=True)
print(f"[compile] finished in {time.time() - t0:.1f}s", flush=True)

try:
    report = compiled.compilation_report()
    print("[report] === compilation report ===", flush=True)
    print(report, flush=True)
except Exception as e:  # noqa: BLE001
    print("[report] unavailable:", e, flush=True)

print("[out-dir] contents:", flush=True)
for root, _, files in sorted(os.walk(OUT)):
    for f in files:
        p = os.path.join(root, f)
        print(f"  {os.path.getsize(p):>12}  {p}", flush=True)

