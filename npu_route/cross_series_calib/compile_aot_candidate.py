import hashlib
import sys
from pathlib import Path

from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.qualcomm import target

source = Path(sys.argv[1])
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
soc = target.Target(soc_model=target.SocModel.SM8650)
print(f"[aot] Compiling {source.name} for SM8650 into {output}...", flush=True)
result = aot_compile.aot_compile(str(source), output_dir=str(output), target=[soc], keep_going=True)
try:
    print(result.compilation_report(), flush=True)
except Exception as e:
    print(f"Report error: {e}", flush=True)

for file in output.rglob("*"):
    if file.is_file():
        print(f"  {file.name}: {file.stat().st_size} bytes, sha256: {hashlib.sha256(file.read_bytes()).hexdigest()}", flush=True)
print("[aot] Done.", flush=True)
