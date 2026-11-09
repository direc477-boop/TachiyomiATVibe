import sys
from tflite.Model import Model
from tflite import BuiltinOperator
from tflite.Conv2DOptions import Conv2DOptions

op_map = {v: k for k, v in BuiltinOperator.__dict__.items() if not k.startswith("__") and isinstance(v, int)}

path = sys.argv[1]
buf = open(path, "rb").read()
model = Model.GetRootAsModel(buf, 0)
subgraph = model.Subgraphs(0)

def tensor_shape(idx):
    if idx < 0:
        return []
    t = subgraph.Tensors(idx)
    return [t.Shape(j) for j in range(t.ShapeLength())]

def tensor_name(idx):
    if idx < 0:
        return ""
    t = subgraph.Tensors(idx)
    name = t.Name()
    return name.decode('utf-8') if name else ""

print(f"Total ops: {subgraph.OperatorsLength()}")
rf = 1
jump = 1

for i in range(subgraph.OperatorsLength()):
    op = subgraph.Operators(i)
    code = model.OperatorCodes(op.OpcodeIndex()).BuiltinCode()
    name = op_map.get(code, f"UNKNOWN_{code}")
    
    in_tensors = [op.Inputs(j) for j in range(op.InputsLength())]
    out_tensors = [op.Outputs(j) for j in range(op.OutputsLength())]
    
    in_shapes = [tensor_shape(t) for t in in_tensors]
    out_shapes = [tensor_shape(t) for t in out_tensors]
    
    extra = ""
    if name == "CONV_2D":
        w_shape = in_shapes[1] if len(in_shapes) > 1 else []
        opt_table = op.BuiltinOptions()
        stride_h, stride_w = 1, 1
        dilation_h, dilation_w = 1, 1
        padding = "?"
        if opt_table:
            conv_opts = Conv2DOptions()
            conv_opts.Init(opt_table.Bytes, opt_table.Pos)
            stride_h = conv_opts.StrideH()
            stride_w = conv_opts.StrideW()
            dilation_h = conv_opts.DilationHFactor()
            dilation_w = conv_opts.DilationWFactor()
            padding = "SAME" if conv_opts.Padding() == 0 else "VALID"
        
        if len(w_shape) == 4:
            kh, kw = w_shape[1], w_shape[2]
            eff_k = kh + (kh - 1) * (dilation_h - 1)
            rf += (eff_k - 1) * jump
            jump *= stride_h
            extra = f"kernel={kh}x{kw} stride={stride_h}x{stride_w} dil={dilation_h}x{dilation_w} pad={padding} w_shape={w_shape} -> running_rf={rf} jump={jump}"
        else:
            extra = f"w_shape={w_shape}"
            
    print(f"[{i:02d}] {name:15s} in={in_shapes} out={out_shapes} {extra}")

print(f"\nFINAL CONV RECEPTIVE FIELD (analytic): {rf} x {rf} pixels at input resolution")
