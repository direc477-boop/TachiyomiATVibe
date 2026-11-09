"""QuickSRNetLarge structural analysis: exact op sequence + analytic receptive field.

Parses the flatbuffer directly (tflite schema bindings). For each operator:
opcode name + input tensor shapes. Conv layers feed the RF recursion
(r += (k-1)*j; j *= stride); global spatial reductions are flagged as GLOBAL
coupling; depth-to-space/resize are RF-neutral rearrangements.
Usage: python -I qsrnet_ops_dump.py MODEL_PATH
"""
import sys

import numpy as np
from tflite import Model as tf_model_mod
from tflite import Conv2DOptions as tf_conv_mod
from tflite import BuiltinOperator as tf_opcodes

path = sys.argv[1]
buf = open(path, "rb").read()
model = tf_model_mod.Model.GetModel(buf, 0)
def op_name(ocode_index):
    builtin = model.OperatorCodes(ocode_index).BuiltinCode()
    try:
        return tf_opcodes.Name(builtin)
    except Exception:  # noqa: BLE001
        return f"code_{builtin}"

subgraph = model.Subgraphs(0)
n_ops = subgraph.OperatorsLength()

def tensor_shape(idx):
    t = subgraph.Tensors(idx)
    sh = t.Shape()
    return list(sh) if sh is not None else []

r_in, j_in = 1, 1  # receptive radius (input px) and jump at the current point
global_ops = []
print(f"=== op sequence ({n_ops} ops) ===", flush=True)
for i in range(n_ops):
    op = subgraph.Operators(i)
    name = op_name(op.OpcodeIndex())
    ins = [tensor_shape(op.Inputs(j)) for j in range(op.InputsLength())]
    outs = [tensor_shape(op.Inputs(0))]  # placeholder, replaced below
    outs = [tensor_shape(op.Outputs(j)) for j in range(op.OutputsLength())]
    detail = ""
    if name in ("CONV_2D", "TRANSPOSE_CONV") and len(ins) >= 2:
        wshape = ins[1]
        kh, kw = (wshape[1], wshape[2]) if len(wshape) == 4 else (0, 0)
        try:
            opts = tf_conv_mod.Conv2DOptions.Conv2DOptions(buf, op.Options().Pos)
            sh_, sw_ = opts.StrideH(), opts.StrideW()
        except Exception:  # noqa: BLE001
            sh_, sw_ = 1, 1
        detail = f"k={kh}x{kw} s={sh_}x{sw_} w={wshape}"
        if name == "CONV_2D":
            r_in += (kh - 1) * j_in
            j_in *= sh_
    elif name in ("MEAN", "SUM", "REDUCE_MAX", "REDUCE_MIN") and len(ins) >= 1 and len(ins[0]) >= 3:
        detail = f"reduce on {ins[0]} axes={tensor_shape(op.Inputs(1)) if op.InputsLength() > 1 else '?'}"
        if len(ins[0]) >= 3:
            global_ops.append(name)
    elif name == "DEPTH_TO_SPACE" or name == "LOGISTIC" or name == "LEAKY_RELU":
        detail = "RF-neutral"
    print(f"[{i:02d}] {name:20s} in={ins} out={outs} {detail}", flush=True)

print(f"=== analytic conv-stack RF at depth of last conv: radius={r_in} RF={2 * r_in + 1} jump={j_in} ===", flush=True)
print(f"=== global spatial reductions found: {global_ops if global_ops else 'NONE'} ===", flush=True)
print("OPS_DUMP_DONE", flush=True)
