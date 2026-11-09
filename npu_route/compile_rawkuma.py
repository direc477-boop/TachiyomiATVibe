import hashlib
import sys
from pathlib import Path

from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.qualcomm import target

source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/data/mcv2_w8a8_rawkuma.tflite')
output = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('/data/aot')
output.mkdir(exist_ok=True)
soc = target.Target(soc_model=target.SocModel.SM8650)
result = aot_compile.aot_compile(str(source), output_dir=str(output), target=[soc], keep_going=True)
print(result.compilation_report(), flush=True)
for file in output.rglob('*'):
    if file.is_file():
        print(file.name, file.stat().st_size, hashlib.sha256(file.read_bytes()).hexdigest(), flush=True)
