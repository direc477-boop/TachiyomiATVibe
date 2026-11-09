"""QuickSRNetLarge receptive-field + tiling probe v2 (numeric, synthetic, report-only).

Test 1: border mutation of width bw; measure how deep (output px) the influence
extends past the mutation ring. For a conv stack with half-RF `a` (input px),
affected band depth = 4a -> a = h/4, RF = 2a+1. Cross-check at bw=16 and bw=32.
Test 2: two overlapping tiles agree bit-exactly in their shared output region
when the comparison region is >= a from each tile's own edge.
CPU interpreter, deterministic. No image vision, no quality claims.
Usage: python -I rf_tile_probe.py MODEL_PATH
"""
import sys

import numpy as np

from ai_edge_litert.interpreter import Interpreter

MODEL = sys.argv[1]

interp = Interpreter(model_path=MODEL, num_threads=2)
interp.allocate_tensors()
inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
is_u8 = inp["dtype"] == np.uint8
s, z = 0.003921568859368563, 0.0

def encode(x01):
    return np.clip(np.round(x01 / s + z), 0, 255).astype(np.uint8) if is_u8 else x01.astype(np.float32)

def run(x01):
    interp.set_tensor(inp["index"], encode(x01))
    interp.invoke()
    y = interp.get_tensor(out["index"])
    return y.astype(np.float64) / (255.0 if is_u8 else 1.0)

rng = np.random.default_rng(7)
base = np.clip(rng.random((1, 128, 128, 3)) * 0.9 + 0.05, 0.0, 1.0)
out_base = run(base)

print(f"=== test 1: influence depth per border ring (model={MODEL}, u8={is_u8}) ===", flush=True)
a_est = None
for bw in (16, 32):
    mut = base.copy()
    mut[:, :bw, :] = 1.0 - mut[:, :bw, :]
    mut[:, -bw:, :] = 1.0 - mut[:, -bw:, :]
    mut[:, :, :bw] = 1.0 - mut[:, :, :bw]
    mut[:, :, -bw:] = 1.0 - mut[:, :, -bw:]
    d = np.abs(run(mut) - out_base).max(axis=(0, -1))  # (512,512)
    changed = d > 0
    inner = changed[bw * 4:512 - bw * 4, bw * 4:512 - bw * 4]
    depth = 0
    for k in range(inner.shape[0]):
        if inner[k, :].any() or inner[:, k].any() or inner[-1 - k, :].any() or inner[:, -1 - k].any():
            depth = k + 1
    a = depth / 4.0
    print(f"ring={bw:2d}px changed_pixels={int(changed.sum())} influence_depth={depth}out_px -> a={a:g}in_px RF~{2 * a + 1:g}", flush=True)
    if bw == 32:
        a_est = int(np.ceil(a))

if a_est and a_est > 0:
    print(f"=== test 2: tile-stitch agreement, halo a={a_est}px ===", flush=True)
    ov = 2 * a_est + 4
    src = np.clip(rng.random((256, 256, 3)) * 0.9 + 0.05, 0.0, 1.0)
    tA = src[0:128, 0:128][None]
    tB = src[0:128, 128 - ov:256 - ov][None]
    oA, oB = run(tA), run(tB)
    lo, hi = 128 - ov + a_est, 128 - a_est  # shared source cols, >=a from each tile edge
    regionA = oA[:, lo * 4:hi * 4]
    regionB = oB[:, (lo - (128 - ov)) * 4:(hi - (128 - ov)) * 4]
    md = float(np.abs(regionA - regionB).max())
    print(f"overlap={ov}px comparison_band=[{lo},{hi})src diff={md:.3e} identical={md == 0.0}", flush=True)
    print(f"TILE_SPEC: tile=128x128 input_halo={a_est}px/side output_crop={4 * a_est}px/side min_overlap={2 * a_est}px", flush=True)
print("RF_PROBE_DONE", flush=True)
