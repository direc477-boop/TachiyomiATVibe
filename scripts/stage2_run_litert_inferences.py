#!/usr/bin/env python3
"""Stage 2: Run LiteRT inference on the 33 Chapter 34 tensors using the recipe-calibrated W8A8 model."""

import time
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter

CH34_DIR = Path("/mnt/c/Users/ADMIN/Downloads/ch34_compare")
IN_NPZ = CH34_DIR / "ch34_inputs.npz"
OUT_NPZ = CH34_DIR / "ch34_recipe_outs.npz"
MODEL_PATH = Path("/scratch/mcv2_576x832/recipe_w8a8_run/mcv2_576x832_w8a8_recipe.tflite")

def main():
    print("=== Stage 2: LiteRT Inference on Chapter 34 ===", flush=True)
    t0 = time.time()

    data = np.load(IN_NPZ)["inputs"] # (33, 1, 5, 832, 576)
    print(f"Loaded {len(data)} input tensors of shape {data.shape[1:]}", flush=True)

    print(f"Loading model: {MODEL_PATH}", flush=True)
    interp = Interpreter(model_path=str(MODEL_PATH), num_threads=4)
    interp.allocate_tensors()
    in_idx = interp.get_input_details()[0]["index"]
    out_idx = interp.get_output_details()[0]["index"]

    outs = []
    for i, arr in enumerate(data):
        t_page = time.time()
        interp.set_tensor(in_idx, arr)
        interp.invoke()
        out = interp.get_tensor(out_idx)[0].copy().astype(np.float32)
        outs.append(out)
        if (i + 1) % 5 == 0 or (i + 1) == len(data):
            print(f"  Inference page {i+1:02d}/33 in {time.time()-t_page:.2f}s", flush=True)

    outs_stack = np.stack(outs, axis=0) # (33, 832, 576, 3)
    np.savez_compressed(OUT_NPZ, outputs=outs_stack)
    print(f"Stage 2 done in {time.time()-t0:.1f}s. Saved {OUT_NPZ} ({OUT_NPZ.stat().st_size:,} bytes)", flush=True)

if __name__ == "__main__":
    main()
