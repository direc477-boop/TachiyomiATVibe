"""QuickSRNetLarge TFLite inspection + numeric synthetic smoke (report-only).

Prints signature/tensor facts (names, shapes, dtypes, quantization params)
and runs ONE deterministic synthetic input through the CPU interpreter.
Numeric evidence only - no image vision, no quality claims.
Usage: python -I inspect_qsrnet.py MODEL_PATH
"""
import sys

import numpy as np

from ai_edge_litert.interpreter import Interpreter

model = sys.argv[1]
interp = Interpreter(model_path=model, num_threads=2)
interp.allocate_tensors()

print("=== signatures ===", flush=True)
print(interp.get_signature_list(), flush=True)

print("=== input details ===", flush=True)
for td in interp.get_input_details():
    q = td.get("quantization")
    print(f"name={td['name']} shape={td['shape']} dtype={td['dtype']} index={td['index']} quant={q}", flush=True)

print("=== output details ===", flush=True)
for td in interp.get_output_details():
    q = td.get("quantization")
    print(f"name={td['name']} shape={td['shape']} dtype={td['dtype']} index={td['index']} quant={q}", flush=True)

print("=== op census ===", flush=True)
try:
    ops = interp._get_ops_details()
    names = {}
    for op in ops:
        n = op.get("opname", "?")
        names[n] = names.get(n, 0) + 1
    print(f"total ops={len(ops)} census={dict(sorted(names.items(), key=lambda kv: -kv[1]))}", flush=True)
except Exception as e:  # noqa: BLE001
    print(f"op census unavailable: {type(e).__name__}: {e}", flush=True)

# numeric synthetic smoke: deterministic ramp+checker pattern in [0,1]
inp = interp.get_input_details()[0]
out = interp.get_output_details()[0]
h, w = int(inp["shape"][1]), int(inp["shape"][2])
yy, xx = np.mgrid[0:h, 0:w]
base = (xx * 255 // 16) % 256 / 255.0
checker = ((yy // 8 + xx // 8) % 2) * 0.25
img3 = np.stack([base, base * 0.8 + checker, 1.0 - base * 0.5], axis=-1).astype(np.float32)
x = img3[np.newaxis]
if inp["dtype"] == np.uint8:
    q = inp.get("quantization")
    if isinstance(q, dict):
        s = float(np.asarray(q.get("scales") or [1.0]).ravel()[0])
        z = float(np.asarray(q.get("zero_points") or [0]).ravel()[0])
    else:  # tuple form (scale, zero_point)
        s = float(np.asarray(q[0]).ravel()[0])
        z = float(np.asarray(q[1]).ravel()[0])
    print(f"=== input quantization === scale={s!r} zero_point={z!r}", flush=True)
    x = np.clip(np.round(x / s + z), 0, 255).astype(np.uint8)
interp.set_tensor(inp["index"], x)
interp.invoke()
y = interp.get_tensor(out["index"])
print(f"=== smoke input stats === dtype={x.dtype} shape={x.shape} min={x.min()} max={x.max()}", flush=True)
print(f"=== smoke output stats === dtype={y.dtype} shape={y.shape} min={y.min():.6f} max={y.max():.6f} mean={y.mean():.6f} finite={np.isfinite(y.astype(np.float64)).all()}", flush=True)
print("QSRNET_INSPECT_DONE", flush=True)
