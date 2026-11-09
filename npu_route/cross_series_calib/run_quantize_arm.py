import os
import sys
import time
import numpy as np

from ai_edge_litert.interpreter import Interpreter
from litert_quantizer import quantizer as lq
from litert_quantizer import recipe_manager
from litert_quantizer.calibrator import CalibrationMode
from litert_quantizer.qtyping import TFLOperationName

MODEL_IN = sys.argv[1]
MODEL_OUT = sys.argv[2]
CALIB_NPZ = sys.argv[3]
TAG = sys.argv[4] if len(sys.argv) > 4 else "quant"

print(f"==================================================", flush=True)
print(f"[{TAG}] Starting W8A8 Quantization: {TAG}", flush=True)
print(f"[{TAG}] Model in: {MODEL_IN}", flush=True)
print(f"[{TAG}] Model out: {MODEL_OUT}", flush=True)
print(f"[{TAG}] Calib NPZ: {CALIB_NPZ}", flush=True)

t0 = time.time()
data = np.load(CALIB_NPZ)["inputs"]
print(f"[{TAG}] Loaded {data.shape[0]} calibration inputs of shape {data.shape[1:]}", flush=True)

interp_in = Interpreter(model_path=MODEL_IN)
input_details = interp_in.get_input_details()
input_name = input_details[0]["name"]
print(f"[{TAG}] Model input tensor name: '{input_name}', shape: {input_details[0]['shape']}", flush=True)

qt = lq.Quantizer(float_model=MODEL_IN)
rp = recipe_manager.RecipeManager()
rp.add_static_config(
    regex=".*",
    operation_name=TFLOperationName.CONV_2D,
    activation_num_bits=8,
    weight_num_bits=8,
)
qt.load_quantization_recipe(rp.get_quantization_recipe())
print(f"[{TAG}] Conv-only wi8_ai8 recipe loaded", flush=True)

calib_data = {"serving_default": [{input_name: arr} for arr in data]}

print(f"[{TAG}] Calibrating using CALIBRATION_PROFILER_BASED...", flush=True)
calib_result = qt.calibrate(calib_data, num_threads=2, mode=CalibrationMode.CALIBRATION_PROFILER_BASED)
print(f"[{TAG}] Calibrated profiler-based ({time.time()-t0:.1f}s)", flush=True)

qt.quantize(calibration_result=calib_result, serialize_to_path=MODEL_OUT)
print(f"[{TAG}] Quantized and exported to {MODEL_OUT} ({time.time()-t0:.1f}s)", flush=True)

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

print(f"[{TAG}] Final size: {os.path.getsize(MODEL_OUT)} bytes (source: {os.path.getsize(MODEL_IN)} bytes)", flush=True)
print(f"[{TAG}] Total tensors: {n_total}, dtypes: {dt_count}", flush=True)
print(f"[{TAG}] Quantized tensors: {n_quant} (int8={n_int8})", flush=True)
print(f"[{TAG}] SUCCESS", flush=True)
