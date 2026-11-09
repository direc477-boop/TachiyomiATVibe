import sys
import tflite
from tflite.Model import Model
from tflite import BuiltinOperator
from tflite.Conv2DOptions import Conv2DOptions

# Reverse map
op_map = {}
for k, v in BuiltinOperator.__dict__.items():
    if not k.startswith("__") and isinstance(v, int):
        op_map[v] = k

path = sys.argv[1]
buf = open(path, "rb").read()
model = Model.GetRootAsModel(buf, 0)
subgraph = model.Subgraphs(0)

print(f"Total ops: {subgraph.OperatorsLength()}")
for i in range(subgraph.OperatorsLength()):
    op = subgraph.Operators(i)
    code = model.OperatorCodes(op.OpcodeIndex()).BuiltinCode()
    name = op_map.get(code, f"UNKNOWN_{code}")
    print(f"op {i:02d}: {name}")
