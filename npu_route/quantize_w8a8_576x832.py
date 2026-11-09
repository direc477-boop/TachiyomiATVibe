"""W8A8 smoke probe for 832x576 MCV2: conv-only static wi8_ai8 quantize B6 FP32 + census.

Strictly synthetic calibration inputs (4 of 10 samples under 3GB cap).
No user image files.
"""
import os
import sys
import time

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from litert_quantizer import quantizer as lq
from litert_quantizer import recipe_manager
from litert_quantizer.calibrator import CalibrationMode
from litert_quantizer.qtyping import TFLOperationName

MODEL_IN = sys.argv[1] if len(sys.argv) > 1 else "/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite"
MODEL_OUT = sys.argv[2] if len(sys.argv) > 2 else "/scratch/mcv2_576x832/mcv2_576x832_w8a8.tflite"
CALIB = sys.argv[3] if len(sys.argv) > 3 else "/scratch/mcv2_576x832/calib_inputs_576x832.npz"
N_SAMPLES = int(sys.argv[4]) if len(sys.argv) > 4 else 4

t0 = time.time()
data = np.load(CALIB)["inputs"][:N_SAMPLES]
print(f"[w8a8] Loaded {data.shape[0]} synthetic calibration inputs of shape {data.shape[1:]}", flush=True)

# Discover input tensor name dynamically
interp_in = Interpreter(model_path=MODEL_IN)
input_details = interp_in.get_input_details()
input_name = input_details[0]["name"]
print(f"[w8a8] Discovered model input tensor name: '{input_name}', shape: {input_details[0]['shape']}", flush=True)

qt = lq.Quantizer(float_model=MODEL_IN)
rp = recipe_manager.RecipeManager()
rp.add_static_config(
    regex=".*",
    operation_name=TFLOperationName.CONV_2D,
    activation_num_bits=8,
    weight_num_bits=8,
)
qt.load_quantization_recipe(rp.get_quantization_recipe())
print("[w8a8] Conv-only wi8_ai8 recipe loaded", flush=True)

calib_data = {"serving_default": [{input_name: arr} for arr in data]}

calib_result = None
try:
    print("[w8a8] Calibrating using CALIBRATION_PROFILER_BASED...", flush=True)
    calib_result = qt.calibrate(calib_data, num_threads=2, mode=CalibrationMode.CALIBRATION_PROFILER_BASED)
    print(f"[w8a8] Calibrated profiler-based ({time.time()-t0:.1f}s)", flush=True)
    qt.quantize(calibration_result=calib_result, serialize_to_path=MODEL_OUT)
except Exception as e:
    print(f"[w8a8] Profiler path failed ({type(e).__name__}: {str(e)[:200]}); falling back to PRESERVE_ALL_TENSORS under 3GB cap", flush=True)
    calib_result = qt.calibrate(calib_data, num_threads=2, mode=CalibrationMode.CALIBRATION_PRESERVE_ALL_TENSORS)
    qt.quantize(calibration_result=calib_result, serialize_to_path=MODEL_OUT)

print(f"[w8a8] Quantized and exported to {MODEL_OUT} ({time.time()-t0:.1f}s)", flush=True)

# Census details
interp = Interpreter(model_path=MODEL_OUT)
interp.allocate_tensors()
dt_count = {}
n_int8 = 0
n_quant = 0
n_total = 0
for td in interp.get_tensor_details():
    n_total += 1
    dt = str(td["dtype"])
    dt_count[dt] = dt_count.get(dt, 0) + 1
    q = td.get("quantization") or {}
    qs = q.get("scales") if isinstance(q, dict) else None
    if qs is not None and len(qs) > 0:
        n_quant += 1
        if dt == "int8":
            n_int8 += 1

print(f"[w8a8] Model size: {os.path.getsize(MODEL_OUT)} bytes (source: {os.path.getsize(MODEL_IN)} bytes)", flush=True)
print(f"[w8a8] Total tensors: {n_total}, dtypes: {dt_count}", flush=True)
print(f"[w8a8] Quantized tensors: {n_quant} (int8={n_int8})", flush=True)
print("W8A8_QUANT_DONE", flush=True)
