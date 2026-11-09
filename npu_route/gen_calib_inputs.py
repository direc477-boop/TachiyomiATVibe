"""Generate 9 smoke-level calibration inputs for the MCV2 contract.

Contract: FP32 NCHW [1,5,704,512]; ch0 = Rec.601 luma in [0,1]; ch1-4 zero.
2 real-ish pages (gray luma recomputed from preserved user work images) +
7 synthetic manga-like patterns. SMOKE LEVEL ONLY - partition-support probe,
NOT deployment calibration (that needs 500-1000 representative pages).
"""
import os
import sys

import numpy as np
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "/scratch/calib/calib_inputs.npz"
os.makedirs(os.path.dirname(OUT), exist_ok=True)
H, W, C = 704, 512, 5

def luma(rgb01):
    return 0.299 * rgb01[..., 0] + 0.587 * rgb01[..., 1] + 0.114 * rgb01[..., 2]

def pack(gray):
    x = np.zeros((1, C, H, W), dtype=np.float32)
    x[0, 0] = np.clip(gray, 0.0, 1.0).astype(np.float32)
    return x

samples = []

# 2 real-ish: gray luma from preserved user work images (colorized RGB work files)
for p in ("/scratch/in/user_colorize_work_704x512.png",
          "/scratch/in/user_colorize_work_704x512_28.png"):
    img = Image.open(p).convert("RGB")
    if img.size != (W, H):
        img = img.resize((W, H), Image.BILINEAR)
    samples.append(pack(luma(np.asarray(img, dtype=np.float32) / 255.0)))

rng = np.random.default_rng(20261107)

# 3 paper with strokes of varying density
for density in (0.02, 0.08, 0.2):
    g = np.ones((H, W), dtype=np.float32)
    n = int(density * H * W / 40)
    for _ in range(n):
        y0, x0 = rng.integers(0, H), rng.integers(0, W)
        ln = rng.integers(20, 160)
        if rng.random() < 0.5:
            y1, x1 = y0, min(W - 1, x0 + ln)
        else:
            y1, x1 = min(H - 1, y0 + ln), x0
        rr = max(1, int(rng.integers(1, 5)))
        y0lo, y0hi = max(0, y0 - rr), min(H, y0 + rr + 1)
        x0lo, x0hi = max(0, x0 - rr), min(W, x0 + rr + 1)
        y1lo, y1hi = max(0, y1 - rr), min(H, y1 + rr + 1)
        x1lo, x1hi = max(0, x1 - rr), min(W, x1 + rr + 1)
        g[y0lo:y0hi, x0lo:x0hi] = 0.05
        g[y1lo:y1hi, x1lo:x1hi] = 0.05
        ys = np.linspace(y0, y1, max(abs(y1 - y0), 1)).astype(int)
        xs = np.linspace(x0, x1, max(abs(x1 - x0), 1)).astype(int)
        g[ys.clip(0, H - 1), xs.clip(0, W - 1)] = 0.05
    samples.append(pack(g))

# text-like bands
g = np.ones((H, W), dtype=np.float32)
for band in range(0, H, 36):
    n = rng.integers(4, 12)
    for _ in range(n):
        y0 = band + int(rng.integers(0, 20))
        x0 = int(rng.integers(0, W - 200))
        g[y0:y0 + int(rng.integers(8, 16)), x0:x0 + int(rng.integers(80, 200))] = 0.1
samples.append(pack(g))

# smooth gradient ramp
gx = np.linspace(0.0, 1.0, W, dtype=np.float32)[None, :]
samples.append(pack(np.repeat(gx, H, axis=0)))

# uniform noise
samples.append(pack(rng.uniform(size=(H, W)).astype(np.float32)))

# diagonal lines
yy, xx = np.mgrid[0:H, 0:W]
samples.append(pack(((yy + 2 * xx) % 24 < 3).astype(np.float32) * 0.9))

# halftone dots
samples.append(pack((((yy % 8 < 4) & (xx % 8 < 4)).astype(np.float32)) * 0.85))

# near-black stress
samples.append(pack(np.full((H, W), 0.03, dtype=np.float32)))

assert len(samples) == 11, len(samples)
np.savez_compressed(OUT, inputs=np.stack(samples))
print(f"[calib] wrote {len(samples)} inputs to {OUT}, shape {samples[0].shape}")
