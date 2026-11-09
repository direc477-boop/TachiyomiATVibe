import flatbuffers
from pathlib import Path
import numpy as np
from ai_edge_litert import schema_py_generated as s

p = Path(r"C:\Users\ADMIN\.traycer\epics\a6ecb13a-1ffe-4afc-9a81-9eff76afeff1\scratch\mcv2-tflite-trial\gpu_variants\out_b6\mcv2-fp32-b6-int32idx_float32.tflite")
buf = p.read_bytes()
model = s.Model.GetRootAs(buf, 0)
mt = s.ModelT.InitFromObj(model)
sg = mt.subgraphs[0]

dims_704_512 = {704, 512, 352, 256, 176, 128, 88, 64}

print("=== Scanning Tensor Shapes ===")
tensors_with_dims = []
for idx, t in enumerate(sg.tensors):
    shape = list(t.shape) if t.shape is not None else []
    matched = [d for d in shape if d in dims_704_512]
    if matched:
        tensors_with_dims.append((idx, t.name.decode() if t.name else str(idx), shape, matched))
print(f"Found {len(tensors_with_dims)} tensors whose shape contains one of {dims_704_512}")

print("\n=== Scanning Constant Buffers (non-weight data) ===")
buffer_hits = []
for idx, t in enumerate(sg.tensors):
    if t.buffer != 0 and mt.buffers[t.buffer].data is not None:
        raw = bytes(bytearray(mt.buffers[t.buffer].data))
        # check if it's integer data (int32 or int64)
        if t.type in (s.TensorType.INT32, s.TensorType.INT64):
            dt = "<i4" if t.type == s.TensorType.INT32 else "<i8"
            arr = np.frombuffer(raw, dtype=dt)
            matches = [v for v in arr if v in dims_704_512]
            if matches:
                buffer_hits.append((idx, t.name.decode() if t.name else str(idx), t.shape, arr.tolist(), matches))

print(f"Found {len(buffer_hits)} constant buffers containing dimension values:")
for bh in buffer_hits:
    print("  Tensor", bh[0], bh[1], "shape:", bh[2], "values:", bh[3])

print("\n=== Scanning Operators ===")
OPN = {v: k for k, v in vars(s.BuiltinOperator).items() if isinstance(v, int)}
for idx, op in enumerate(sg.operators):
    code = mt.operatorCodes[op.opcodeIndex].builtinCode
    opname = OPN.get(code, str(code))
    if opname in ("RESHAPE", "STRIDED_SLICE", "SLICE", "PACK", "CONCATENATION", "SPLIT"):
        in_names = [sg.tensors[i].name.decode() if sg.tensors[i].name else str(i) for i in op.inputs]
        out_names = [sg.tensors[i].name.decode() if sg.tensors[i].name else str(i) for i in op.outputs]
        print(f"Op {idx}: {opname} inputs={op.inputs} ({in_names}) outputs={op.outputs} ({out_names})")
