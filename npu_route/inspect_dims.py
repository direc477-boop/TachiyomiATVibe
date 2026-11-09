from pathlib import Path
import flatbuffers
from ai_edge_litert import schema_py_generated as s

p = Path(r"//wsl.localhost/Ubuntu/home/admin1/d_npu_aot/mcv2_576x832/out_b5/mcv2-fp32-b5-832x576_nchw_float32.tflite")
buf = p.read_bytes()
m = s.Model.GetRootAs(buf, 0)
mt = s.ModelT.InitFromObj(m)
sg = mt.subgraphs[0]
print("=== NEW 576x832 NCHW B5 MODEL ===")
print("Inputs:", [(sg.tensors[i].name.decode(), list(sg.tensors[i].shape), sg.tensors[i].type) for i in sg.inputs])
print("Outputs:", [(sg.tensors[i].name.decode(), list(sg.tensors[i].shape), sg.tensors[i].type) for i in sg.outputs])
print("Total tensors:", len(sg.tensors), "Total ops:", len(sg.operators))

sigs = []
for sd in mt.signatureDefs:
    sigs.append({
        "key": sd.signatureKey.decode() if isinstance(sd.signatureKey, bytes) else sd.signatureKey,
        "inputs": [(item.name.decode() if isinstance(item.name, bytes) else item.name, item.tensorIndex) for item in sd.inputs],
        "outputs": [(item.name.decode() if isinstance(item.name, bytes) else item.name, item.tensorIndex) for item in sd.outputs],
    })
print("Signatures:", sigs)
