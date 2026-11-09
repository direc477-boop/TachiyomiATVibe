"""W8A16 smoke probe: static_wi8_ai16 quantize B6-v1 FP32 + tensor census.

SMOKE calibration (9 synthetic/recovered samples) - partition-support probe,
not deployment accuracy. Outputs: quantized .tflite + census printout.
"""
import sys
import time

import numpy as np

from litert_quantizer import quantizer as lq
from litert_quantizer import recipe

MODEL_IN = sys.argv[1] if len(sys.argv) > 1 else "/scratch/in/mcv2-fp32-b6-int32idx_float32.tflite"
MODEL_OUT = sys.argv[2] if len(sys.argv) > 2 else "/scratch/out/mcv2_b6_w8a16.tflite"
CALIB = sys.argv[3] if len(sys.argv) > 3 else "/scratch/calib/calib_inputs.npz"
N_SAMPLES = int(sys.argv[4]) if len(sys.argv) > 4 else 4  # memory-bounded: 4 of 11

t0 = time.time()
data = np.load(CALIB)["inputs"][:N_SAMPLES]
print(f"[w8a16] {data.shape[0]} calibration inputs {data.shape[1:]}", flush=True)

qt = lq.Quantizer(float_model=MODEL_IN)
from litert_quantizer import recipe_manager  # noqa: PLC0415
from litert_quantizer.qtyping import TFLOperationName  # noqa: PLC0415

rp = recipe_manager.RecipeManager()
# Conv-only scoped W8A16: sidesteps the 0.11.0 rank-5 scales/zps bug seen on
# the ALL_SUPPORTED recipe; conv is the documented A16 sweet spot on HTP.
rp.add_static_config(
    regex=".*",
    operation_name=TFLOperationName.CONV_2D,
    activation_num_bits=16,
    weight_num_bits=8,
)
qt.load_quantization_recipe(rp.get_quantization_recipe())
print("[w8a16] conv-only recipe loaded", flush=True)

calib_data = {"serving_default": [{"input": arr} for arr in data]}
from litert_quantizer.calibrator import CalibrationMode  # noqa: PLC0415

calib_result = None
try:
    calib_result = qt.calibrate(calib_data, num_threads=2, mode=CalibrationMode.CALIBRATION_PROFILER_BASED)
    print(f"[w8a16] calibrated profiler-based ({time.time()-t0:.0f}s)", flush=True)
    qt.quantize(calibration_result=calib_result, serialize_to_path=MODEL_OUT)
except Exception as e:  # noqa: BLE001
    print(f"[w8a16] profiler path failed ({type(e).__name__}: {str(e)[:200]}); falling back to PRESERVE_ALL_TENSORS under 3GB cap", flush=True)
    calib_result = qt.calibrate(calib_data, num_threads=2, mode=CalibrationMode.CALIBRATION_PRESERVE_ALL_TENSORS)
    qt.quantize(calibration_result=calib_result, serialize_to_path=MODEL_OUT)
print(f"[w8a16] quantized+exported ({time.time()-t0:.0f}s)", flush=True)

# census: tensor dtype/quantization distribution via interpreter
from ai_edge_litert.interpreter import Interpreter  # noqa: PLC0415

interp = Interpreter(model_path=MODEL_OUT)
interp.allocate_tensors()
dt_count = {}
n_int16_act = 0
n_int8_w = 0
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
        if dt == "int16":
            n_int16_act += 1
        elif dt == "int8":
            n_int8_w += 1
import os  # noqa: PLC0415
print(f"[w8a16] size: {os.path.getsize(MODEL_OUT)} B (source {os.path.getsize(MODEL_IN)} B)", flush=True)
print(f"[w8a16] tensors: total={n_total} dtypes={dt_count}", flush=True)
print(f"[w8a16] quantized tensors: {n_quant} (int16={n_int16_act}, int8={n_int8_w})", flush=True)
print("W8A16_QUANT_DONE", flush=True)
