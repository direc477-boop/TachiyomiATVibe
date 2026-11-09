"""Generate calibration inputs from authorized representative assets for 576x832 MCV2.

Sources:
1. Workspace workbench assets under model_workbench/colorizer_litert/scripts/mcv2/assets:
   - page_real_512x704.png
   - page_syn_a_512x704.png
   - page_syn_b_512x704.png
   (Provenance: commit 7f55afa in TachiyomiATVibe repository)
2. Benchmark assets under model_workbench/colorizer_litert/download/coloring_repo_v2/results:
   - result1.jpg, result2.jpg, result3.png, result4.png, result5.png, result6.png
   (Provenance: Apache 2.0 / open license via HF masterofaudio2077/sana-manga-coloring-v2)

Contract:
- FP32 NCHW [1, 5, 832, 576]
- ch0 = Rec.601 luma in [0.0, 1.0]
- ch1-4 = 0.0f
- Strictly numerical processing: zero visual image inspection.
"""
from pathlib import Path
import numpy as np
from PIL import Image

H, W = 832, 576
OUT_NPZ = Path("/scratch/mcv2_576x832/calib_inputs_authorized_576x832.npz")
OUT_NPZ.parent.mkdir(parents=True, exist_ok=True)

IMAGE_PATHS = [
    Path("/scratch/assets/mcv2/page_real_512x704.png"),
    Path("/scratch/assets/mcv2/page_syn_a_512x704.png"),
    Path("/scratch/assets/mcv2/page_syn_b_512x704.png"),
    Path("/scratch/assets/sana_results/result1.jpg"),
    Path("/scratch/assets/sana_results/result2.jpg"),
    Path("/scratch/assets/sana_results/result3.png"),
    Path("/scratch/assets/sana_results/result4.png"),
    Path("/scratch/assets/sana_results/result5.png"),
    Path("/scratch/assets/sana_results/result6.png"),
]

def process_image(path: Path) -> np.ndarray:
    im = Image.open(path).convert("L")
    im_resized = im.resize((W, H), Image.Resampling.LANCZOS)
    gray = np.asarray(im_resized, dtype=np.float32) / 255.0
    gray = np.clip(gray, 0.0, 1.0)
    
    t = np.zeros((1, 5, H, W), dtype=np.float32)
    t[0, 0] = gray
    return t

def main():
    samples = []
    print(f"[calib_gen] Processing {len(IMAGE_PATHS)} authorized representative assets...", flush=True)
    for p in IMAGE_PATHS:
        if not p.exists():
            print(f"[calib_gen] WARNING: {p} does not exist!", flush=True)
            continue
        arr = process_image(p)
        print(f"  Loaded {p.name}: min={arr[0,0].min():.4f}, max={arr[0,0].max():.4f}, mean={arr[0,0].mean():.4f}, std={arr[0,0].std():.4f}", flush=True)
        samples.append(arr)
        
    assert len(samples) > 0, "No calibration samples loaded!"
    stacked = np.stack(samples)
    print(f"[calib_gen] Stacked array shape: {stacked.shape}, dtype={stacked.dtype}", flush=True)
    np.savez_compressed(OUT_NPZ, inputs=stacked)
    print(f"[calib_gen] Saved to {OUT_NPZ} ({OUT_NPZ.stat().st_size} bytes)", flush=True)

if __name__ == "__main__":
    main()
