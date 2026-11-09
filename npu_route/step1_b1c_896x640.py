"""Step 1: Build the static 640x896 B1c ONNX from manga-colorize-fp16.onnx (or mcv2-fp32.onnx).
Rewrites Clip -> NHWC ReLU chain, sets static input/output to [1,5,896,640] and [1,896,640,3],
runs shape inference to populate all 659 value_infos with exact 896x640 intermediate shapes."""
import sys
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto, shape_inference

SCRATCH = Path(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_640x896")
SRC = Path(r"C:\Users\ADMIN\.traycer\epics\a6ecb13a-1ffe-4afc-9a81-9eff76afeff1\scratch\mcv2-tflite-trial\mcv2-fp32.onnx")
DST = SCRATCH / "mcv2-fp32-b1c-896x640.onnx"

m = onnx.load(str(SRC))
g = m.graph

# 1. Update graph input shape to static 896x640
g.input[0].type.tensor_type.shape.dim[2].dim_value = 896
g.input[0].type.tensor_type.shape.dim[3].dim_value = 640

# 2. Terminal Clip -> NHWC chain
clip = next(n for n in g.node if n.op_type == "Clip")
x = clip.input[0]
out = clip.output[0]

one = numpy_helper.from_array(np.array(1.0, dtype=np.float32), "gpu/one_const")
g.initializer.append(one)

nodes = [
    helper.make_node("Transpose", [x], ["gpu/add_nhwc"], perm=[0, 2, 3, 1], name="/Clip/gpu_to_nhwc"),
    helper.make_node("Neg", ["gpu/add_nhwc"], ["gpu/neg_x"], name="/Clip/gpu_neg"),
    helper.make_node("Relu", ["gpu/neg_x"], ["gpu/relu_negx"], name="/Clip/gpu_relu_neg"),
    helper.make_node("Add", ["gpu/add_nhwc", "gpu/relu_negx"], ["gpu/clamped_lo"], name="/Clip/gpu_add_lo"),
    helper.make_node("Sub", ["gpu/add_nhwc", one.name], ["gpu/x_minus_1"], name="/Clip/gpu_sub_one"),
    helper.make_node("Relu", ["gpu/x_minus_1"], ["gpu/relu_over"], name="/Clip/gpu_relu_over"),
    helper.make_node("Sub", ["gpu/clamped_lo", "gpu/relu_over"], [out], name="/Clip/gpu_sub_over"),
]
pos = list(g.node).index(clip)
g.node.remove(clip)
for i, node in enumerate(nodes):
    g.node.insert(pos + i, node)

for o in g.output:
    if o.name == out:
        del o.type.tensor_type.shape.dim[:]
        for d in (1, 896, 640, 3):
            dim = o.type.tensor_type.shape.dim.add()
            dim.dim_value = d
        o.type.tensor_type.elem_type = TensorProto.FLOAT

# 3. Clear old value_info and run shape inference
del g.value_info[:]
inferred = shape_inference.infer_shapes(m)
onnx.checker.check_model(inferred)
onnx.save(inferred, str(DST))
print(f"[step1] saved B1c 896x640: {DST} (value_info count: {len(inferred.graph.value_info)})")
