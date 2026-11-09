"""Step 2: Apply B4 pad-free surgery on 832x576 ONNX."""
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper, shape_inference

SCRATCH = Path(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_576x832")
SRC = SCRATCH / "mcv2-fp32-b1c-832x576.onnx"
DST = SCRATCH / "mcv2-fp32-b4-832x576.onnx"

model = onnx.load(str(SRC))
g = model.graph

shapes = {}
for vi in list(g.value_info) + list(g.input):
    dims = [d.dim_value for d in vi.type.tensor_type.shape.dim]
    if dims:
        shapes[vi.name] = dims

new_nodes = []
replaced = 0
for node in g.node:
    if node.op_type == "Conv":
        pads = [a for a in node.attribute if a.name == "pads"]
        strides = [a for a in node.attribute if a.name == "strides"]
        if pads and any(p != 0 for p in pads[0].ints) and strides and list(strides[0].ints) == [2, 2]:
            p = list(pads[0].ints)
            ph_b, pw_b, ph_e, pw_e = p[0], p[1], p[2], p[3]
            x = node.input[0]
            n, c, h, w = shapes[x]
            ph, pw = ph_b, pw_b
            made = []
            def zero_init(name, dims4):
                arr = np.zeros(dims4, dtype=np.float32)
                made.append(numpy_helper.from_array(arr, name))
                return name
            hp = zero_init(f"{node.name}_b4_zpre_h", [n, c, ph, w])
            hq = zero_init(f"{node.name}_b4_zpost_h", [n, c, ph, w])
            hc = helper.make_node("Concat", [hp, x, hq], [f"{node.name}_b4_padh"],
                                  name=f"{node.name}_b4_concat_h", axis=2)
            wpre = zero_init(f"{node.name}_b4_zpre_w", [n, c, h + 2 * ph, pw])
            wpost = zero_init(f"{node.name}_b4_zpost_w", [n, c, h + 2 * ph, pw])
            wc = helper.make_node("Concat", [wpre, f"{node.name}_b4_padh", wpost],
                                  [f"{node.name}_b4_padw"], name=f"{node.name}_b4_concat_w", axis=3)
            g.initializer.extend(made)
            new_nodes.extend([hc, wc])
            node.input[0] = f"{node.name}_b4_padw"
            pads[0].CopyFrom(helper.make_attribute("pads", [0] * len(p)))
            replaced += 1
    new_nodes.append(node)

print(f"[step2] replaced {replaced} stride-2 padded convs")
del g.node[:]
g.node.extend(new_nodes)
del g.value_info[:]
inferred = shape_inference.infer_shapes(model)
onnx.checker.check_model(inferred)
onnx.save(inferred, str(DST))
print(f"[step2] saved B4 832x576: {DST}")
