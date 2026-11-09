import os
from pathlib import Path
from ai_edge_litert import schema_py_generated as s

p = Path(r"C:\Users\ADMIN\.traycer\epics\a6ecb13a-1ffe-4afc-9a81-9eff76afeff1\scratch\mcv2-tflite-trial\gpu_variants\out_b6\mcv2-fp32-b6-int32idx_float32.tflite")
buf = p.read_bytes()
model = s.Model.GetRootAs(buf, 0)
mt = s.ModelT.InitFromObj(model)
sg = mt.subgraphs[0]
print(f"Inputs: {[sg.tensors[i].name.decode() for i in sg.inputs]} shapes: {[sg.tensors[i].shape for i in sg.inputs]}")
print(f"Outputs: {[sg.tensors[i].name.decode() for i in sg.outputs]} shapes: {[sg.tensors[i].shape for i in sg.outputs]}")
print(f"Total tensors: {len(sg.tensors)}, Total ops: {len(sg.operators)}")

OPN = {v: k for k, v in vars(s.BuiltinOperator).items() if isinstance(v, int)}
op_counts = {}
for op in sg.operators:
    code = mt.operatorCodes[op.opcodeIndex].builtinCode
    nm = OPN.get(code, str(code))
    op_counts[nm] = op_counts.get(nm, 0) + 1
print("Operator counts:", op_counts)
