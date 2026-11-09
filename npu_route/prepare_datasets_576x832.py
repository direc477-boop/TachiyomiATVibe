"""Prepare representative calibration dataset and held-out evaluation dataset for 576x832 MCV2.

Ensures ZERO LEAKAGE between calibration and held-out evaluation sets.
Every sample is documented with:
- Source / Provenance
- Preprocessing steps
- Numerical statistics (min, max, mean, std)
- SHA-256 digest of input tensor
Zero visual image inspection: programmatic numeric tensor preparation only.
"""
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

H, W, C = 832, 576, 5

CALIB_OUT = Path("/scratch/mcv2_576x832/calib_representative_576x832.npz")
EVAL_OUT = Path("/scratch/mcv2_576x832/eval_heldout_576x832.npz")
REPORT_OUT = Path("/scratch/mcv2_576x832/datasets_manifest_576x832.json")


def sha256_arr(arr: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()


def pack_gray(gray: np.ndarray) -> np.ndarray:
    """Pack 2D gray in [0, 1] into NCHW [1, 5, H, W] with hint channels 1-4 = 0."""
    t = np.zeros((1, C, H, W), dtype=np.float32)
    t[0, 0] = np.clip(gray, 0.0, 1.0).astype(np.float32)
    return t


def preprocess_png(path: Path) -> np.ndarray:
    """Preprocess image file: convert to Grayscale (L), resize with Lanczos to (W, H), scale to [0, 1]."""
    im = Image.open(path).convert("L")
    im_resized = im.resize((W, H), Image.Resampling.LANCZOS)
    gray = np.asarray(im_resized, dtype=np.float32) / 255.0
    return pack_gray(gray)


def make_procedural_manga(seed: int) -> np.ndarray:
    """Generate structured synthetic manga page (panels, screentones, speedlines, text)."""
    rng = np.random.default_rng(seed)
    img = Image.new("L", (W, H), 255)
    draw = ImageDraw.Draw(img)

    # Outer border
    draw.rectangle([0, 0, W - 1, H - 1], outline=0, width=6)

    # Panel divisions
    y_split1 = int(H * 0.38)
    y_split2 = int(H * 0.72)
    x_split = int(W * 0.52)

    # Top panel
    draw.rectangle([20, 20, W - 20, y_split1 - 10], outline=0, width=4)
    # Middle left panel
    draw.rectangle([20, y_split1 + 10, x_split - 10, y_split2 - 10], outline=0, width=4)
    # Middle right panel
    draw.rectangle([x_split + 10, y_split1 + 10, W - 20, y_split2 - 10], outline=0, width=4)
    # Bottom panel
    draw.rectangle([20, y_split2 + 10, W - 20, H - 20], outline=0, width=4)

    # Speed lines in top panel
    n_lines = int(rng.integers(30, 50))
    for _ in range(n_lines):
        y = rng.integers(30, y_split1 - 20)
        x_len = rng.integers(int(W * 0.3), int(W * 0.8))
        draw.line([(30, y), (30 + x_len, y)], fill=int(rng.integers(0, 50)), width=int(rng.integers(1, 4)))

    # Radial burst in middle left panel
    cx, cy = int(x_split * 0.5), int((y_split1 + y_split2) * 0.5)
    for a in range(0, 360, 10):
        r_in = rng.integers(20, 45)
        r_out = rng.integers(60, 110)
        rad = math.radians(a)
        x0 = cx + int(r_in * math.cos(rad))
        y0 = cy + int(r_in * math.sin(rad))
        x1 = cx + int(r_out * math.cos(rad))
        y1 = cy + int(r_out * math.sin(rad))
        draw.line([(x0, y0), (x1, y1)], fill=int(rng.integers(20, 60)), width=2)

    # Speech bubble in middle right panel
    bx0, by0 = x_split + 30, y_split1 + 30
    bx1, by1 = W - 40, y_split2 - 40
    draw.ellipse([bx0, by0, bx1, by1], outline=0, width=3, fill=255)
    # Simulated text lines inside bubble
    for ly in range(by0 + 25, by1 - 25, 18):
        tx0 = bx0 + 25
        while tx0 < bx1 - 35:
            seg = int(rng.integers(8, 28))
            draw.line([(tx0, ly), (min(tx0 + seg, bx1 - 35), ly)], fill=0, width=2)
            tx0 += seg + int(rng.integers(6, 14))

    # Screentone halftone dots in bottom panel
    dot_spacing = int(rng.integers(6, 10))
    for dy in range(y_split2 + 20, H - 30, dot_spacing):
        for dx in range(30, W - 30, dot_spacing):
            r = int(rng.integers(1, 3))
            draw.ellipse([dx, dy, dx + r, dy + r], fill=int(rng.integers(60, 110)))

    # Hatching strokes across bottom panel
    for hx in range(-200, W, 18):
        draw.line([(hx, H - 25), (hx + 120, y_split2 + 25)], fill=int(rng.integers(70, 120)), width=2)

    arr = np.array(img, dtype=np.float32) / 255.0
    # Paper noise
    noise = rng.normal(0, 0.015, arr.shape).astype(np.float32)
    gray = np.clip(arr + noise, 0.0, 1.0)
    return pack_gray(gray)


def owner_pattern_grid() -> np.ndarray:
    t = np.zeros((1, C, H, W), dtype=np.float32)
    xs = np.arange(W) % 64 < 3
    ys = np.arange(H) % 64 < 3
    t[0, 0] = np.where(xs[None, :] | ys[:, None], np.float32(0.05), np.float32(0.95))
    return t


def random_seed(seed: int = 1234) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = np.zeros((1, C, H, W), dtype=np.float32)
    t[0, 0] = rng.uniform(0.0, 1.0, size=(H, W)).astype(np.float32)
    return t


def horizontal_gradient() -> np.ndarray:
    t = np.zeros((1, C, H, W), dtype=np.float32)
    gx = np.linspace(0.0, 1.0, W, dtype=np.float32)[None, :]
    t[0, 0] = np.repeat(gx, H, axis=0)
    return t


def get_stats(arr: np.ndarray) -> dict:
    c0 = arr[0, 0]
    return {
        "shape": list(arr.shape),
        "min": float(c0.min()),
        "max": float(c0.max()),
        "mean": float(c0.mean()),
        "std": float(c0.std()),
        "sha256": sha256_arr(arr),
    }


def main():
    print("==================================================", flush=True)
    print("[datasets] Building Representative Calib & Held-Out Eval Datasets", flush=True)

    assets_dir = Path("/scratch/mcv2_576x832/assets")

    # 1. Calibration Set (6 samples)
    calib_items = [
        ("calib_01_page_syn_a", "Workbench asset page_syn_a_512x704.png (commit 7f55afa)", assets_dir / "page_syn_a_512x704.png", None),
        ("calib_02_page_syn_b", "Workbench asset page_syn_b_512x704.png (commit 7f55afa)", assets_dir / "page_syn_b_512x704.png", None),
        ("calib_03_procedural_panels", "Procedural manga layout (seed=101, panels/speedlines/hatching)", None, 101),
        ("calib_04_procedural_screentone", "Procedural manga layout (seed=102, halftones/bubbles/text)", None, 102),
        ("calib_05_procedural_burst", "Procedural manga layout (seed=103, radial burst/heavy ink)", None, 103),
        ("calib_06_procedural_hatch", "Procedural manga layout (seed=104, diagonal hatching/dialogue)", None, 104),
    ]

    calib_tensors = []
    calib_manifest = []
    print("\n--- Calibration Set (6 samples) ---", flush=True)
    for name, desc, file_path, seed in calib_items:
        if file_path is not None:
            tensor = preprocess_png(file_path)
        else:
            tensor = make_procedural_manga(seed)
        stats = get_stats(tensor)
        calib_tensors.append(tensor)
        entry = {"name": name, "description": desc, "stats": stats}
        calib_manifest.append(entry)
        print(f"  [{name}] {desc}\n    mean={stats['mean']:.4f}, std={stats['std']:.4f}, sha256={stats['sha256'][:16]}...", flush=True)

    # 2. Held-Out Evaluation Set (6 samples - STRICT ZERO LEAKAGE)
    eval_items = [
        ("eval_01_page_real_heldout", "Workbench asset page_real_512x704.png (commit 7f55afa) - HELD OUT REAL MANGA", assets_dir / "page_real_512x704.png", None),
        ("eval_02_procedural_heldout_a", "Procedural manga layout (seed=201) - HELD OUT UNSEEN LAYOUT", None, 201),
        ("eval_03_procedural_heldout_b", "Procedural manga layout (seed=202) - HELD OUT UNSEEN LAYOUT", None, 202),
        ("eval_04_diagnostic_grid", "Diagnostic owner_pattern_grid (baseline comparability)", "grid", None),
        ("eval_05_diagnostic_random", "Diagnostic random_seed1234 (baseline comparability)", "random", 1234),
        ("eval_06_diagnostic_gradient", "Diagnostic horizontal_gradient (baseline comparability)", "gradient", None),
    ]

    eval_tensors = []
    eval_manifest = []
    print("\n--- Held-Out Evaluation Set (6 samples - Zero Leakage) ---", flush=True)
    for name, desc, src, seed in eval_items:
        if isinstance(src, Path):
            tensor = preprocess_png(src)
        elif src == "grid":
            tensor = owner_pattern_grid()
        elif src == "random":
            tensor = random_seed(seed)
        elif src == "gradient":
            tensor = horizontal_gradient()
        elif seed is not None:
            tensor = make_procedural_manga(seed)
        else:
            raise ValueError(f"Unknown item: {name}")

        stats = get_stats(tensor)
        eval_tensors.append(tensor)
        entry = {"name": name, "description": desc, "stats": stats}
        eval_manifest.append(entry)
        print(f"  [{name}] {desc}\n    mean={stats['mean']:.4f}, std={stats['std']:.4f}, sha256={stats['sha256'][:16]}...", flush=True)

    # Save datasets
    np.savez_compressed(CALIB_OUT, inputs=np.stack(calib_tensors))
    np.savez_compressed(EVAL_OUT, inputs=np.stack(eval_tensors))
    print(f"\n[datasets] Saved calib dataset -> {CALIB_OUT} (shape: {len(calib_tensors)}, {H}, {W})", flush=True)
    print(f"[datasets] Saved eval dataset -> {EVAL_OUT} (shape: {len(eval_tensors)}, {H}, {W})", flush=True)

    manifest = {
        "calibration_set": calib_manifest,
        "held_out_evaluation_set": eval_manifest,
        "input_contract": {
            "shape": [1, C, H, W],
            "dtype": "float32",
            "ch0_definition": "Rec.601 luma in [0.0, 1.0]",
            "ch1_4_definition": "zeros",
        },
    }
    REPORT_OUT.write_text(json.dumps(manifest, indent=2))
    print(f"[datasets] Manifest written to {REPORT_OUT}", flush=True)


if __name__ == "__main__":
    main()
