import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL = sys.argv[1]
interp = Interpreter(model_path=MODEL, num_threads=2)
interp.allocate_tensors()
inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
is_u8 = inp["dtype"] == np.uint8

def encode(x):
    return np.clip(np.round(x * 255.0), 0, 255).astype(np.uint8) if is_u8 else x.astype(np.float32)

def run(x):
    interp.set_tensor(inp["index"], encode(x))
    interp.invoke()
    y = interp.get_tensor(out["index"])
    return y.astype(np.float64) / (255.0 if is_u8 else 1.0)

rng = np.random.default_rng(42)
base = rng.uniform(0.1, 0.9, size=(1, 128, 128, 3)).astype(np.float32)
out_base = run(base)

# Test single pixel mutation at center (64, 64)
mut_center = base.copy()
mut_center[0, 64, 64, :] = 1.0 - mut_center[0, 64, 64, :]
out_mut = run(mut_center)

diff = np.abs(out_mut - out_base)[0] # (512, 512, 3)
diff_max = diff.max(axis=-1) # (512, 512)
changed = diff_max > 1e-5

# Find bounding box of changed pixels in output
rows = np.where(changed.any(axis=1))[0]
cols = np.where(changed.any(axis=0))[0]

print(f"=== Single pixel mutation at input (64, 64) ===")
print(f"Output changed rows: [{rows.min()}, {rows.max()}] -> span = {rows.max() - rows.min() + 1} px")
print(f"Output changed cols: [{cols.min()}, {cols.max()}] -> span = {cols.max() - cols.min() + 1} px")
print(f"Expected span for 4x upscaler with RF=27: 27 * 4 = 108 px (or around center 256)")
print(f"Actual output radius: {(rows.max() - rows.min() + 1) / 2} out_px -> {(rows.max() - rows.min() + 1) / 8} in_px radius")

# Test border mutation: mutate only left border cols 0..15 (bw = 16)
mut_border = base.copy()
bw = 16
mut_border[0, :, :bw, :] = 1.0 - mut_border[0, :, :bw, :]
out_border = run(mut_border)

diff_border = np.abs(out_border - out_base)[0].max(axis=-1)
# mutated cols in input: 0..15. In output: 0..63 is directly mutated.
# Beyond col 63 (col 64..511): how far does change reach?
cols_changed_beyond = np.where(diff_border[:, bw*4:].any(axis=0))[0]
if len(cols_changed_beyond) > 0:
    max_reach_out = cols_changed_beyond.max() + 1
    max_reach_in = max_reach_out / 4.0
    print(f"\n=== Border mutation (left {bw} cols) ===")
    print(f"Influence extends {max_reach_out} output pixels past boundary ({max_reach_in} input pixels)")
    print(f"Beyond col {bw*4 + max_reach_out}, diff is bit-exactly zero: {np.all(diff_border[:, bw*4 + max_reach_out:] == 0)}")
else:
    print("No change beyond mutated band!")
