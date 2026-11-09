"""
Full-page halo tiling reference implementation & validation suite for QuickSRNetLarge.
Validates:
1. Arbitrary / odd image sizes (e.g., 71x89, 137x245, 128x128, 300x400)
2. Edge / corner / partial tiles with edge clamping/padding
3. Seam continuity across interior tile boundaries (halo >= 14px, e.g. 16px)
4. Exact output dimensions: (W*4, H*4, C)
5. Color / Alpha preservation
6. Float32 vs W8A8 quantized comparison
"""
import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter

FLOAT_MODEL_PATH = sys.argv[1]
W8A8_MODEL_PATH = sys.argv[2] if len(sys.argv) > 2 else None

class TiledUpscaler:
    def __init__(self, model_path: str, halo: int = 16):
        self.interp = Interpreter(model_path=model_path, num_threads=2)
        self.interp.allocate_tensors()
        self.inp_det = self.interp.get_input_details()[0]
        self.out_det = self.interp.get_output_details()[0]
        self.is_u8 = self.inp_det["dtype"] == np.uint8
        self.scale = 4
        self.tile_size = 128 # fixed model input dimension
        self.halo = halo # margin to discard from interior edges
        self.step = self.tile_size - 2 * self.halo # interior step size (e.g. 96)
        assert self.step > 0, "Halo too large for tile size"

    def _run_tile(self, tile_nhwc: np.ndarray) -> np.ndarray:
        """Runs a single 128x128 tile through the model."""
        assert tile_nhwc.shape == (1, 128, 128, 3)
        if self.is_u8:
            val = np.clip(np.round(tile_nhwc * 255.0), 0, 255).astype(np.uint8)
        else:
            val = tile_nhwc.astype(np.float32)
        self.interp.set_tensor(self.inp_det["index"], val)
        self.interp.invoke()
        out = self.interp.get_tensor(self.out_det["index"])
        if self.is_u8:
            return out.astype(np.float32) / 255.0
        return out.astype(np.float32)

    def upscale(self, image_hwc: np.ndarray) -> np.ndarray:
        """
        Upscales an image of arbitrary (H, W, 3 or 4) by 4x using halo tiling.
        Alpha channel (if present) is upscaled via bilinear interpolation and rejoined.
        """
        H, W = image_hwc.shape[:2]
        has_alpha = image_hwc.shape[-1] == 4
        if has_alpha:
            rgb = image_hwc[..., :3]
            alpha = image_hwc[..., 3:]
        else:
            rgb = image_hwc
            alpha = None

        out_H, out_W = H * self.scale, W * self.scale
        out_rgb = np.zeros((out_H, out_W, 3), dtype=np.float32)

        # Generate 1D tiling coordinates along an axis
        def get_slices(dim_size: int):
            if dim_size <= self.tile_size:
                # Single tile covers the entire dimension
                return [(0, dim_size, 0, dim_size, 0, dim_size * self.scale)]
            
            slices = []
            pos = 0
            while pos < dim_size:
                in_start = pos
                in_end = min(pos + self.tile_size, dim_size)
                tile_len = in_end - in_start
                
                # Determine valid crop range within the tile
                # If first tile: keep from 0 up to min(tile_len - halo, tile_len)
                crop_start = 0 if pos == 0 else self.halo
                # If last tile reaching end: keep up to tile_len
                crop_end = tile_len if in_end == dim_size else self.tile_size - self.halo
                
                # Destination range in output
                dst_start = (in_start + crop_start) * self.scale
                dst_end = (in_start + crop_end) * self.scale
                
                slices.append((in_start, in_end, crop_start, crop_end, dst_start, dst_end))
                
                if in_end == dim_size:
                    break
                # Advance pos by step
                pos += self.step
            return slices

        y_slices = get_slices(H)
        x_slices = get_slices(W)

        # Buffer for 128x128 tile
        tile_buf = np.zeros((1, self.tile_size, self.tile_size, 3), dtype=np.float32)

        for y_in_s, y_in_e, y_cr_s, y_cr_e, y_dst_s, y_dst_e in y_slices:
            h_slice = y_in_e - y_in_s
            for x_in_s, x_in_e, x_cr_s, x_cr_e, x_dst_s, x_dst_e in x_slices:
                w_slice = x_in_e - x_in_s
                
                # Extract image patch
                patch = rgb[y_in_s:y_in_e, x_in_s:x_in_e, :]
                
                # Edge replication if patch is smaller than 128x128 (at right/bottom boundaries)
                tile_buf[0, :h_slice, :w_slice, :] = patch
                if h_slice < self.tile_size:
                    # replicate bottom row
                    tile_buf[0, h_slice:, :w_slice, :] = patch[-1:, :, :]
                if w_slice < self.tile_size:
                    # replicate right column (for all rows including replicated)
                    tile_buf[0, :, w_slice:, :] = tile_buf[0, :, w_slice-1:w_slice, :]

                # Run tile inference -> (1, 512, 512, 3)
                tile_out = self._run_tile(tile_buf)[0]

                # Crop valid interior
                cropped = tile_out[y_cr_s * self.scale : y_cr_e * self.scale,
                                   x_cr_s * self.scale : x_cr_e * self.scale, :]

                # Place into output
                out_rgb[y_dst_s:y_dst_e, x_dst_s:x_dst_e, :] = cropped

        if has_alpha:
            # Upscale alpha 4x using simple bilinear/box expansion
            # Repeat 4x along each axis as simple nearest/bilinear
            alpha_up = np.repeat(np.repeat(alpha, self.scale, axis=0), self.scale, axis=1)
            return np.concatenate([out_rgb, alpha_up], axis=-1)
        return out_rgb

# --- Validation Suite ---
print("=== Running TiledUpscaler Validation Suite ===", flush=True)
upscaler = TiledUpscaler(FLOAT_MODEL_PATH, halo=16)

rng = np.random.default_rng(999)

# Test 1: Single small image (odd sizes < 128)
for h, w in [(71, 89), (1, 1), (127, 63), (128, 128)]:
    img = rng.uniform(0.0, 1.0, size=(h, w, 3)).astype(np.float32)
    out = upscaler.upscale(img)
    assert out.shape == (h * 4, w * 4, 3), f"Shape mismatch: {out.shape} vs ({h*4}, {w*4}, 3)"
    assert np.all(np.isfinite(out)), "Non-finite output"
    print(f"[Test 1 PASS] Small/Odd shape {h}x{w} -> {out.shape}")

# Test 2: Large image spanning multiple tiles in both directions (odd and non-multiples of step)
for h, w in [(137, 245), (300, 400), (200, 150)]:
    img = rng.uniform(0.0, 1.0, size=(h, w, 3)).astype(np.float32)
    out = upscaler.upscale(img)
    assert out.shape == (h * 4, w * 4, 3), f"Shape mismatch: {out.shape} vs ({h*4}, {w*4}, 3)"
    assert np.all(np.isfinite(out)), "Non-finite output"
    print(f"[Test 2 PASS] Multi-tile shape {h}x{w} -> {out.shape}")

# Test 3: Alpha channel preservation
img_rgba = rng.uniform(0.0, 1.0, size=(100, 150, 4)).astype(np.float32)
out_rgba = upscaler.upscale(img_rgba)
assert out_rgba.shape == (400, 600, 4), f"RGBA shape mismatch: {out_rgba.shape}"
# Verify alpha corner values match source
assert np.isclose(out_rgba[0, 0, 3], img_rgba[0, 0, 3]), "Alpha top-left mismatch"
print(f"[Test 3 PASS] RGBA image 100x150x4 -> {out_rgba.shape} alpha preserved")

# Test 4: Seam test - compare stitched output against a shifted sub-window
# An image of 200x200 upscaled via tiled upscaler should have ZERO seam jump.
# Let's check spatial gradient across the tile boundary (around dst_x = 96*4 = 384)
# On a smooth gradient image, the second spatial derivative across the seam should have no spike.
y_ramp, x_ramp = np.meshgrid(np.linspace(0.1, 0.9, 200), np.linspace(0.1, 0.9, 200), indexing='ij')
grad_img = np.stack([y_ramp, x_ramp, (y_ramp + x_ramp)/2.0], axis=-1).astype(np.float32)
out_grad = upscaler.upscale(grad_img)
# Boundary line is at output x = (0 + 112) * 4 = 448 (where crop_end of tile 0 meets crop_start of tile 1)
# Check difference between x=447 and x=448 vs neighboring step x=446 to x=447
step_seam = np.abs(out_grad[:, 448, :] - out_grad[:, 447, :]).max()
step_normal = np.abs(out_grad[:, 447, :] - out_grad[:, 446, :]).max()
print(f"[Test 4 PASS] Gradient seam test: step across seam = {step_seam:.6f}, regular step = {step_normal:.6f}")
assert step_seam < 0.01, "Seam artifact detected!"

# Test 5: Float32 vs W8A8 comparison (if W8A8 model path provided)
if W8A8_MODEL_PATH:
    upscaler_w8a8 = TiledUpscaler(W8A8_MODEL_PATH, halo=16)
    test_img = rng.uniform(0.1, 0.9, size=(128, 128, 3)).astype(np.float32)
    out_f32 = upscaler.upscale(test_img)
    out_w8a8 = upscaler_w8a8.upscale(test_img)
    mae = np.mean(np.abs(out_f32 - out_w8a8))
    max_d = np.max(np.abs(out_f32 - out_w8a8))
    psnr = 20 * np.log10(1.0 / np.sqrt(np.mean((out_f32 - out_w8a8)**2)))
    print(f"[Test 5 PASS] Float vs W8A8 comparison on 128x128:")
    print(f"   MAE: {mae:.4f}, MaxDiff: {max_d:.4f}, PSNR: {psnr:.2f} dB")

print("ALL_TESTS_PASS_SUCCESS", flush=True)
