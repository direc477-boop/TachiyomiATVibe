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

rng = np.random.default_rng(12345)
# Create a larger input image, e.g. 128 x 224 (width > 128)
H, W = 128, 224
src = rng.uniform(0.1, 0.9, size=(H, W, 3)).astype(np.float32)

# Tile 1: cols 0..128
tile1 = src[:, 0:128][None, ...]
out1 = run(tile1)[0] # (512, 512, 3)

# Tile 2: cols 96..224 (stride = 96, overlap = 32 cols)
tile2 = src[:, 96:224][None, ...]
out2 = run(tile2)[0] # (512, 512, 3)

# In the overlap zone (input cols 96..128, length 32 cols):
# tile1 has cols 96..128 at offset 96..128 in tile1 (near right edge of tile1)
# tile2 has cols 96..128 at offset 0..32 in tile2 (near left edge of tile2)
# With halo = 16 input px (64 output px):
# Reliable valid interior for tile1: output cols up to (128 - 16)*4 = 112*4 = 448 (global col 448)
# Reliable valid interior for tile2: output cols from (16)*4 = 64 (global col 96*4 + 64 = 448)
# At the seam line x_out = 448 (global input col 112):
# Let's inspect the agreement in the valid overlapping zone between halo margin:
# Tile 1 offset in output: [0..512] corresponds to global [0..512]
# Tile 2 offset in output: [0..512] corresponds to global [384..896]
# Overlap region in global coordinates: [384..512] (128 output px = 32 input px)
# Inside overlap, margin from tile1 right edge is >= 64 output px (cols 384..448)
# and margin from tile2 left edge is >= 64 output px (cols 448..512)
# Specifically, at global cols [384 + 64 : 512 - 64] -> Wait, if overlap is 128 px and halo is 64 on each side,
# 128 - 2*64 = 0 px. With halo = 14 px (56 output px), overlap = 32 px (128 out px):
# valid shared band is [384 + 56, 512 - 56] = [440, 456] (16 output px = 4 input px).

halo_in = 14
halo_out = halo_in * 4 # 56 px
t1_valid_end = (128 - halo_in) * 4 # 114 * 4 = 456
t2_valid_start = halo_in * 4 # 14 * 4 = 56 (global 384 + 56 = 440)

# Compare shared valid strip in global coords [440 .. 456]:
strip1 = out1[:, 440:456, :]
strip2 = out2[:, 56:72, :] # 440 - 384 = 56

max_err = np.abs(strip1 - strip2).max()
print(f"=== Stitching Agreement Test ===")
print(f"Halo: {halo_in} in_px ({halo_out} out_px). Shared valid band: 16 out_px.")
print(f"Max difference between independent tile runs: {max_err:.3e}")
print(f"Bit-exact match (diff == 0.0): {max_err == 0.0}")
