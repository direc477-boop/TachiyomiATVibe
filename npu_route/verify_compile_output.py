"""Verify the compiled apply_plugin artifact: parse, signature contract, attribution strings."""
import hashlib
import os
import re
import sys

from ai_edge_litert.interpreter import Interpreter

ART = sys.argv[1]
print(f"[verify] artifact: {ART}")
print(f"[verify] size: {os.path.getsize(ART)} bytes")

h = hashlib.sha256()
with open(ART, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 22), b""):
        h.update(chunk)
print(f"[verify] sha256: {h.hexdigest()}")

data = open(ART, "rb").read()
markers = {}
for pat in (rb"Qualcomm[ -~]{0,40}", rb"SM8650", rb"[Vv]75[ -~]{0,20}", rb"HTP[ -~]{0,30}",
            rb"serve[Tt]ing_default", rb"qairt[ -~]{0,30}", rb"2\.50\.0[ -~]{0,20}"):
    hits = sorted({m.decode("ascii", "ignore").strip() for m in re.findall(pat, data)})[:4]
    if hits:
        markers[pat.decode()] = hits
for k, v in markers.items():
    print(f"[verify] marker {k}: {v}")

interp = Interpreter(model_path=ART)
try:
    sigs = interp.get_signature_list()
    print("[verify] signatures:", sigs)
except Exception as e:  # noqa: BLE001
    print("[verify] signature list failed:", e)
try:
    interp.allocate_tensors()
    ids = interp.get_input_details()
    od = interp.get_output_details()
    for t in ids:
        print(f"[verify] input: {t['name']} {t['shape']} {t['dtype']} index={t['index']}")
    for t in od:
        print(f"[verify] output: {t['name']} {t['shape']} {t['dtype']} index={t['index']}")
except Exception as e:  # noqa: BLE001
    print("[verify] tensor alloc failed (expected if NPU-only):", type(e).__name__, str(e)[:300])
print("[verify] flatbuffer PARSE OK")
