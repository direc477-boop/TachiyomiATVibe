import time
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter

p = Path(r"C:\Users\ADMIN\.traycer\epics\a6ecb13a-1ffe-4afc-9a81-9eff76afeff1\scratch\mcv2-tflite-trial\gpu_variants\out_b6\mcv2-fp32-b6-int32idx_float32.tflite")
print(f"Loading {p}...")
interp = Interpreter(model_path=str(p))
print("Allocating default tensors...")
interp.allocate_tensors()
in_det = interp.get_input_details()
out_det = interp.get_output_details()
print("Default input shape:", in_det[0]["shape"])
print("Default output shape:", out_det[0]["shape"])

# Try resizing
print("\nAttempting resize_tensor_input to [1, 5, 832, 576]...")
try:
    interp.resize_tensor_input(in_det[0]["index"], [1, 5, 832, 576])
    print("resize_tensor_input called. Now calling allocate_tensors()...")
    interp.allocate_tensors()
    in_det2 = interp.get_input_details()
    out_det2 = interp.get_output_details()
    print("Resized input shape:", in_det2[0]["shape"])
    print("Resized output shape:", out_det2[0]["shape"])
    
    # Try inference with dummy synthetic input
    x = np.zeros((1, 5, 832, 576), dtype=np.float32)
    x[0, 0] = 0.5
    interp.set_tensor(in_det2[0]["index"], x)
    t0 = time.time()
    interp.invoke()
    out = interp.get_tensor(out_det2[0]["index"])
    print(f"Invoke SUCCESS in {time.time() - t0:.2f}s! Output shape: {out.shape}, range: [{out.min():.3f}, {out.max():.3f}]")
except Exception as e:
    import traceback
    print(f"Resize FAILED: {type(e).__name__}: {e}")
    traceback.print_exc()
