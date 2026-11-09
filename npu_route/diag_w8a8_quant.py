import flatbuffers
from pathlib import Path
from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.interpreter import Interpreter

p = Path("/scratch/mcv2_576x832/mcv2_576x832_w8a8.tflite")
buf = p.read_bytes()
m = s.Model.GetRootAs(buf, 0)
mt = s.ModelT.InitFromObj(m)
sg = mt.subgraphs[0]

OPN = {v: k for k, v in vars(s.BuiltinOperator).items() if isinstance(v, int)}
T = {0: "f32", 1: "f16", 2: "i32", 3: "u8", 5: "i64", 9: "f64", 8: "i8"}

print(f"Total tensors in flatbuffer: {len(sg.tensors)}")
print(f"Total operators: {len(sg.operators)}")

# Check tensor types and quantization
type_counts = {}
quant_tensors = 0
for idx, t in enumerate(sg.tensors):
    tname = t.name.decode() if isinstance(t.name, bytes) else str(t.name)
    type_counts[t.type] = type_counts.get(t.type, 0) + 1
    qp = t.quantization
    if qp is not None:
        has_scales = qp.scale is not None and len(qp.scale) > 0
        has_zps = qp.zeroPoint is not None and len(qp.zeroPoint) > 0
        if has_scales:
            quant_tensors += 1
            if quant_tensors <= 10:
                print(f"Quantized tensor {idx}: name={tname[:50]} type={t.type} shape={t.shape} scales_len={len(qp.scale)} zps_len={len(qp.zeroPoint) if has_zps else 0}")

print(f"Summary tensor types: {type_counts}")
print(f"Total tensors with quantization parameters: {quant_tensors}")

# Check operators and their input/output types
op_hist = {}
for idx, op in enumerate(sg.operators):
    code = mt.operatorCodes[op.opcodeIndex].builtinCode
    opnm = OPN.get(code, str(code))
    op_hist[opnm] = op_hist.get(opnm, 0) + 1
    if opnm in ("CONV_2D", "QUANTIZE", "DEQUANTIZE"):
        if op_hist[opnm] <= 3:
            in_types = [sg.tensors[i].type for i in op.inputs]
            out_types = [sg.tensors[i].type for i in op.outputs]
            print(f"Sample {opnm}: inputs={op.inputs} types={in_types} -> outputs={op.outputs} types={out_types}")

print(f"Operator histogram: {op_hist}")
