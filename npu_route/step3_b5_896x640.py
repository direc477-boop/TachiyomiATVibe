"""Step 3: Apply B5 CRD->DCR Gather rewrite on 896x640 ONNX."""
from pathlib import Path
import numpy as np
import onnx
from onnx import helper, numpy_helper, shape_inference

SCRATCH = Path(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_640x896")
SRC = SCRATCH / "mcv2-fp32-b4-896x640.onnx"
DST = SCRATCH / "mcv2-fp32-b5-896x640.onnx"

model = onnx.load(str(SRC))
g = model.graph

shapes = {}
for vi in list(g.value_info) + list(g.input) + list(g.output):
    dims = [d.dim_value for d in vi.type.tensor_type.shape.dim]
    if dims:
        shapes[vi.name] = dims

new_nodes = []
replaced = 0
for node in g.node:
    if (node.op_type == "DepthToSpace" and
        [a.s.decode() for a in node.attribute if a.name == "mode"] == ["CRD"] and
        [a.i for a in node.attribute if a.name == "blocksize"] == [2]):
        x = node.input[0]
        dims = shapes.get(x)
        n, c, h, w = dims
        cp = c // 4
        r = 2
        idx = np.array(
            [4 * (t % cp) + 2 * (t // (r * cp)) + ((t % (r * cp)) // cp) for t in range(c)],
            dtype=np.int64)
        g.initializer.append(numpy_helper.from_array(idx, node.name + "_b5_dcr_idx"))
        gnode = helper.make_node("Gather", [x, node.name + "_b5_dcr_idx"],
                                 [node.name + "_b5_reordered"], name=node.name + "_b5_gather", axis=1)
        dnode = helper.make_node("DepthToSpace", [node.name + "_b5_reordered"], list(node.output),
                                 name=node.name + "_b5_d2s_dcr", blocksize=2, mode="DCR")
        new_nodes.extend([gnode, dnode])
        replaced += 1
    else:
        new_nodes.append(node)

print(f"[step3] replaced {replaced} DepthToSpace CRD nodes with Gather+DCR")
del g.node[:]
g.node.extend(new_nodes)
del g.value_info[:]
inferred = shape_inference.infer_shapes(model)
onnx.checker.check_model(inferred)
onnx.save(inferred, str(DST))
print(f"[step3] saved B5 896x640: {DST}")

# Parity check vs FP16 ONNX reference on deterministic synthetic input
import onnxruntime as ort
rng = np.random.default_rng(999)
x = rng.random((1, 5, 896, 640), dtype=np.float32)

ref_path = r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\npu-handoff\fp16-onnx\manga-colorize-fp16.onnx"
s_ref = ort.InferenceSession(ref_path, providers=["CPUExecutionProvider"])
out_ref = s_ref.run(None, {"input": x})[0] # NCHW [1,3,896,640]
out_ref_nhwc = np.transpose(out_ref, (0, 2, 3, 1)) # NHWC [1,896,640,3]

s_b5 = ort.InferenceSession(str(DST), providers=["CPUExecutionProvider"])
out_b5 = s_b5.run(None, {"input": x})[0] # NHWC [1,896,640,3]

max_diff = float(np.max(np.abs(out_ref_nhwc.astype(np.float64) - out_b5.astype(np.float64))))
mean_diff = float(np.mean(np.abs(out_ref_nhwc.astype(np.float64) - out_b5.astype(np.float64))))
print(f"=== PARITY CHECK: B5 896x640 ONNX vs FP16 Reference ONNX ===")
print(f"ref_nhwc shape={out_ref_nhwc.shape} min={out_ref_nhwc.min():.5f} max={out_ref_nhwc.max():.5f} mean={out_ref_nhwc.mean():.5f}")
print(f"out_b5 shape={out_b5.shape} min={out_b5.min():.5f} max={out_b5.max():.5f} mean={out_b5.mean():.5f}")
print(f"max_abs_diff={max_diff:.3e} mean_abs_diff={mean_diff:.3e}")
assert max_diff < 5e-4, f"Parity divergence too large: {max_diff}"
print("PARITY_CHECK_PASSED")
