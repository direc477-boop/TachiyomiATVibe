"""Host-side semantic gate: compiled _fallback.tflite vs preserved B6-v1 original.

Both run on host CPU interpreter with identical deterministic inputs.
Bitwise match => partition composition did not alter graph semantics.
(NPU partition itself cannot execute on host - that is E's device lane.)
"""
import hashlib
import sys

import numpy as np

from ai_edge_litert.interpreter import Interpreter

ORIG = sys.argv[1]
FALLBACK = sys.argv[2]

W, H, C = 512, 704, 5  # contract: FP32 NCHW [1,5,704,512] input 'input'


def make_inputs(seed=1234):
    rng = np.random.default_rng(seed)
    # deterministic mixed pattern: seeded noise + slow gradient (covers value range)
    x = rng.standard_normal((1, C, H, W)).astype(np.float32)
    grad = np.linspace(-1.0, 1.0, W, dtype=np.float32)[None, None, None, :]
    return x * 0.5 + grad


def run_model(path, x):
    interp = Interpreter(model_path=path, num_threads=4)
    interp.allocate_tensors()
    ids = interp.get_input_details()
    od = interp.get_output_details()
    print(f"[verify] {path}")
    print(f"  input[0]: {ids[0]['name']} {ids[0]['shape']} {ids[0]['dtype']}")
    print(f"  output[0]: {od[0]['name']} {od[0]['shape']} {od[0]['dtype']}")
    interp.set_tensor(ids[0]["index"], np.ascontiguousarray(x, dtype=ids[0]["dtype"]))
    interp.invoke()
    return interp.get_tensor(od[0]["index"])


def tensor_hash(a):
    return hashlib.sha256(np.ascontiguousarray(a).tobytes()).hexdigest()


x = make_inputs()
out_orig = run_model(ORIG, x)
out_fb = run_model(FALLBACK, x)

h_orig, h_fb = tensor_hash(out_orig), tensor_hash(out_fb)
print(f"[verify] orig  shape={out_orig.shape} sha256={h_orig}")
print(f"[verify] fback shape={out_fb.shape} sha256={h_fb}")
if out_orig.shape != out_fb.shape:
    print("[verify] FAIL: shape mismatch")
    sys.exit(2)
diff = np.abs(out_orig.astype(np.float64) - out_fb.astype(np.float64))
print(f"[verify] max_abs_diff={diff.max():.6g} mean_abs_diff={diff.mean():.6g}")
bitwise = np.array_equal(out_orig, out_fb)
print(f"[verify] bitwise_equal={bitwise}")
print("[verify] PASS" if bitwise or diff.max() == 0 else "[verify] FAIL: outputs differ")
sys.exit(0 if bitwise else 3)
