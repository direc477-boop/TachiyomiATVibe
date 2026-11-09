"""Direct onnx2tf execution of B5 896x640 ONNX -> TFLite."""
import os
import sys
from pathlib import Path

# Set memory-conservative environment variables
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"

SCRATCH = Path(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_640x896")
SRC_ONNX = SCRATCH / "mcv2-fp32-b5-896x640.onnx"
OUTDIR = SCRATCH / "out_b5"

import onnx2tf
print(f"[onnx2tf] running conversion of {SRC_ONNX} -> {OUTDIR}...")
onnx2tf.convert(
    input_onnx_file_path=str(SRC_ONNX),
    output_folder_path=str(OUTDIR),
    copy_onnx_input_output_names_to_tflite=True,
    non_verbose=True,
)
print("[onnx2tf] conversion finished successfully!")
