#!/usr/bin/env python3
"""Recalibrates the SM8650 MCV2 model (576x832) using the author's PyTorch recipe
calibration tensors and compiles to SM8650 Qualcomm NPU AOT artifact.
"""

import hashlib
import os
import sys
import time
from pathlib import Path

import numpy as np

# LiteRT and Quantizer imports
from ai_edge_litert.interpreter import Interpreter
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.qualcomm import target
from litert_quantizer import quantizer as lq
from litert_quantizer import recipe_manager
from litert_quantizer.calibrator import CalibrationMode
from litert_quantizer.qtyping import TFLOperationName

MODEL_IN = Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
CALIB_NPZ = Path("/scratch/mcv2_576x832/recipe_calibration_80.npz")
OUT_DIR = Path("/scratch/mcv2_576x832/recipe_w8a8_run")
OUT_DIR.mkdir(parents=True, exist_ok=True)

QUANT_OUT = OUT_DIR / "mcv2_576x832_w8a8_recipe.tflite"
AOT_OUT_DIR = OUT_DIR / "aot_out"
AOT_OUT_DIR.mkdir(parents=True, exist_ok=True)


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def main():
    print(f"=== Starting Recipe-Calibrated W8A8 Pipeline ===", flush=True)
    print(f"Base Float Model: {MODEL_IN} ({MODEL_IN.stat().st_size:,} bytes)", flush=True)
    print(f"Calibration NPZ:  {CALIB_NPZ} ({CALIB_NPZ.stat().st_size:,} bytes)", flush=True)
    print(f"Quantized Output: {QUANT_OUT}", flush=True)
    print(f"AOT Output Dir:   {AOT_OUT_DIR}", flush=True)

    t0 = time.time()
    calib_data_arr = np.load(CALIB_NPZ)["inputs"]
    print(f"Loaded {calib_data_arr.shape[0]} calibration inputs of shape {calib_data_arr.shape[1:]}, dtype {calib_data_arr.dtype}", flush=True)

    interp_in = Interpreter(model_path=str(MODEL_IN))
    input_details = interp_in.get_input_details()
    input_name = input_details[0]["name"]
    print(f"Model input tensor: '{input_name}', shape: {input_details[0]['shape']}", flush=True)

    # 1. Setup Quantizer with conv-only W8A8 recipe
    qt = lq.Quantizer(float_model=str(MODEL_IN))
    rp = recipe_manager.RecipeManager()
    rp.add_static_config(
        regex=".*",
        operation_name=TFLOperationName.CONV_2D,
        activation_num_bits=8,
        weight_num_bits=8,
    )
    qt.load_quantization_recipe(rp.get_quantization_recipe())
    print(f"Conv-only wi8_ai8 quantization recipe configured.", flush=True)

    # 2. Calibrate
    calib_data = {"serving_default": [{input_name: arr} for arr in calib_data_arr]}
    print(f"Running CALIBRATION_PROFILER_BASED on {len(calib_data_arr)} samples...", flush=True)
    t_cal = time.time()
    calib_result = qt.calibrate(calib_data, num_threads=4, mode=CalibrationMode.CALIBRATION_PROFILER_BASED)
    print(f"Calibration completed in {time.time() - t_cal:.1f}s", flush=True)

    # 3. Quantize and export
    t_q = time.time()
    qt.quantize(calibration_result=calib_result, serialize_to_path=str(QUANT_OUT))
    print(f"Quantization completed in {time.time() - t_q:.1f}s", flush=True)
    print(f"Quantized model: {QUANT_OUT} ({QUANT_OUT.stat().st_size:,} bytes, sha256={sha256_of_file(QUANT_OUT)})", flush=True)

    # Verify tensor dtypes
    interp = Interpreter(model_path=str(QUANT_OUT))
    interp.allocate_tensors()
    n_int8 = sum(1 for td in interp.get_tensor_details() if "int8" in str(td["dtype"]))
    print(f"Quantized model tensor count: {len(interp.get_tensor_details())} (int8 tensors: {n_int8})", flush=True)

    # 4. AOT Compile for Qualcomm SM8650
    print(f"\n=== AOT Compiling for Qualcomm SM8650 NPU ===", flush=True)
    soc = target.Target(soc_model=target.SocModel.SM8650)
    t_aot = time.time()
    result = aot_compile.aot_compile(str(QUANT_OUT), output_dir=str(AOT_OUT_DIR), target=[soc], keep_going=True)
    print(f"AOT compilation completed in {time.time() - t_aot:.1f}s", flush=True)

    try:
        print(f"Compilation Report:\n{result.compilation_report()}", flush=True)
    except Exception as e:
        print(f"Report fetch warning: {e}", flush=True)

    print("\nGenerated AOT Artifacts:")
    for f in AOT_OUT_DIR.rglob("*"):
        if f.is_file():
            print(f"  {f.name}: {f.stat().st_size:,} bytes, sha256: {sha256_of_file(f)}", flush=True)

    print(f"\nPipeline finished in {time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
