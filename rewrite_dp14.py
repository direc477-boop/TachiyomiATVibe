import onnx
from onnx import helper, numpy_helper, TensorProto
import numpy as np
import onnxruntime as ort

SRC = r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\light-colorizer-512-trial\generator_512_sq2rs.onnx"
DST = r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\light-colorizer-512-trial\generator_512_sq2rs_r4_safe.onnx"

print(f"Loading {SRC}...")
model = onnx.load(SRC)
g = model.graph

inits = {t.name: numpy_helper.to_array(t) for t in g.initializer}
init_map = {t.name: t for t in g.initializer}
nodes_to_remove = []
new_nodes = []
new_inits = []

# ====================================================================
# PART 1: Attention 4D 1x1 Conv Rewrite for qkv and proj across 6 blocks
# ====================================================================
print("Rewriting Attention QKV and Proj to 4D 1x1 Convs across 6 blocks...")
for b_idx in range(6):
    prefix = f"/encoder/backbone/stages_3/blocks/blocks.{b_idx}/token_mixer"
    
    # 1. qkv path
    r_in = next(n for n in g.node if n.name == f"{prefix}/Reshape")
    t_in = next(n for n in g.node if n.name == f"{prefix}/Transpose")
    qkv_mm = next(n for n in g.node if n.name == f"{prefix}/qkv/MatMul")
    r1 = next(n for n in g.node if n.name == f"{prefix}/Reshape_1")
    t1 = next(n for n in g.node if n.name == f"{prefix}/Transpose_1")
    sp = next(n for n in g.node if n.name == f"{prefix}/Split")
    sq0 = next(n for n in g.node if n.name == f"{prefix}/Squeeze")
    sq1 = next(n for n in g.node if n.name == f"{prefix}/Squeeze_1")
    sq2 = next(n for n in g.node if n.name == f"{prefix}/Squeeze_2")
    
    # 2. proj path
    t3 = next(n for n in g.node if n.name == f"{prefix}/Transpose_3")
    r2 = next(n for n in g.node if n.name == f"{prefix}/Reshape_2")
    proj_mm = next(n for n in g.node if n.name == f"{prefix}/proj/MatMul")
    proj_add = next(n for n in g.node if n.name == f"{prefix}/proj/Add")
    t4 = next(n for n in g.node if n.name == f"{prefix}/Transpose_4")
    r3 = next(n for n in g.node if n.name == f"{prefix}/Reshape_3")
    
    nodes_to_remove.extend([
        r_in, t_in, qkv_mm, r1, t1, sp, sq0, sq1, sq2,
        r2, proj_mm, proj_add, t4, r3
    ])
    
    input_4d = r_in.input[0] # [1, 512, 16, 16] from norm/BatchNormalization
    
    # --- Rewriting qkv to 4D 1x1 Conv ---
    W_qkv = inits[qkv_mm.input[1]] # [512, 1536]
    W_qkv_conv = W_qkv.T.reshape(1536, 512, 1, 1).astype(np.float32)
    w_qkv_name = f"{prefix}/qkv_conv_weight"
    t_w_qkv = numpy_helper.from_array(W_qkv_conv, w_qkv_name)
    new_inits.append(t_w_qkv)
    init_map[w_qkv_name] = t_w_qkv
    
    qkv_conv_out = f"{prefix}/qkv_conv_out"
    node_qkv_conv = helper.make_node(
        "Conv",
        inputs=[input_4d, w_qkv_name],
        outputs=[qkv_conv_out],
        name=f"{prefix}/qkv_1x1_conv"
    )
    new_nodes.append(node_qkv_conv)
    
    # Flatten spatial (16, 16) -> 256 tokens and transpose to [1, 256, 1536]
    flat_shape_name = "qkv_flat_shape"
    if flat_shape_name not in init_map:
        t_flat_sh = helper.make_tensor(flat_shape_name, TensorProto.INT64, [3], [1, 1536, 256])
        new_inits.append(t_flat_sh)
        init_map[flat_shape_name] = t_flat_sh
        
    qkv_flat = f"{prefix}/qkv_flat"
    node_qkv_flat = helper.make_node(
        "Reshape",
        inputs=[qkv_conv_out, flat_shape_name],
        outputs=[qkv_flat],
        name=f"{prefix}/qkv_flat_rs"
    )
    qkv_t = f"{prefix}/qkv_t"
    node_qkv_t = helper.make_node(
        "Transpose",
        inputs=[qkv_flat],
        outputs=[qkv_t],
        name=f"{prefix}/qkv_transpose",
        perm=[0, 2, 1]
    )
    new_nodes.extend([node_qkv_flat, node_qkv_t])
    
    # Split into Q, K, V [1, 256, 512] each
    split_sizes_name = f"{prefix}/qkv_split_sizes"
    if split_sizes_name not in init_map:
        split_sizes_tensor = helper.make_tensor(split_sizes_name, TensorProto.INT64, [3], [512, 512, 512])
        new_inits.append(split_sizes_tensor)
        init_map[split_sizes_name] = split_sizes_tensor
    
    q_raw = f"{prefix}/q_raw"
    k_raw = f"{prefix}/k_raw"
    v_raw = f"{prefix}/v_raw"
    
    opset = model.opset_import[0].version
    if opset >= 13:
        new_split = helper.make_node(
            "Split",
            inputs=[qkv_t, split_sizes_name],
            outputs=[q_raw, k_raw, v_raw],
            name=f"{prefix}/qkv_split_axis2",
            axis=2
        )
    else:
        new_split = helper.make_node(
            "Split",
            inputs=[qkv_t],
            outputs=[q_raw, k_raw, v_raw],
            name=f"{prefix}/qkv_split_axis2",
            axis=2,
            split=[512, 512, 512]
        )
    new_nodes.append(new_split)
    
    rs_head_shape_name = "qkv_head_reshape_shape"
    if rs_head_shape_name not in init_map:
        rs_tensor = helper.make_tensor(rs_head_shape_name, TensorProto.INT64, [4], [1, 256, 16, 32])
        new_inits.append(rs_tensor)
        init_map[rs_head_shape_name] = rs_tensor
        
    for raw_name, final_out in [
        (q_raw, sq0.output[0]),
        (k_raw, sq1.output[0]),
        (v_raw, sq2.output[0])
    ]:
        rs_out = f"{raw_name}_rs"
        node_rs = helper.make_node(
            "Reshape",
            inputs=[raw_name, rs_head_shape_name],
            outputs=[rs_out],
            name=f"{raw_name}_reshape"
        )
        node_tr = helper.make_node(
            "Transpose",
            inputs=[rs_out],
            outputs=[final_out],
            name=f"{raw_name}_transpose",
            perm=[0, 2, 1, 3]
        )
        new_nodes.extend([node_rs, node_tr])
        
    # --- Rewriting proj to 4D 1x1 Conv ---
    # t3 is [1, 256, 16, 32]. Reshape to [1, 16, 16, 512], transpose to [1, 512, 16, 16]
    proj_spatial_sh_name = "proj_spatial_shape"
    if proj_spatial_sh_name not in init_map:
        t_proj_sh = helper.make_tensor(proj_spatial_sh_name, TensorProto.INT64, [4], [1, 16, 16, 512])
        new_inits.append(t_proj_sh)
        init_map[proj_spatial_sh_name] = t_proj_sh
        
    t3_out = t3.output[0]
    t3_spatial = f"{prefix}/t3_spatial"
    node_t3_rs = helper.make_node(
        "Reshape",
        inputs=[t3_out, proj_spatial_sh_name],
        outputs=[t3_spatial],
        name=f"{prefix}/t3_spatial_rs"
    )
    t3_nchw = f"{prefix}/t3_nchw"
    node_t3_tr = helper.make_node(
        "Transpose",
        inputs=[t3_spatial],
        outputs=[t3_nchw],
        name=f"{prefix}/t3_nchw_tr",
        perm=[0, 3, 1, 2]
    )
    new_nodes.extend([node_t3_rs, node_t3_tr])
    
    W_proj = inits[proj_mm.input[1]] # [512, 512]
    W_proj_conv = W_proj.T.reshape(512, 512, 1, 1).astype(np.float32)
    w_proj_name = f"{prefix}/proj_conv_weight"
    t_w_proj = numpy_helper.from_array(W_proj_conv, w_proj_name)
    new_inits.append(t_w_proj)
    init_map[w_proj_name] = t_w_proj
    
    bias_name = proj_add.input[0] if proj_add.input[0] in inits else proj_add.input[1]
    
    final_proj_out = r3.output[0] # connects to layer_scale_1/Mul
    node_proj_conv = helper.make_node(
        "Conv",
        inputs=[t3_nchw, w_proj_name, bias_name],
        outputs=[final_proj_out],
        name=f"{prefix}/proj_1x1_conv"
    )
    new_nodes.append(node_proj_conv)

print("Attention 4D 1x1 Conv rewrites complete.")

# ====================================================
# PART 2: Safe GroupNorm Decomposition Rewrite
# ====================================================
print("Rewriting 23 GroupNorm / InstanceNormalization chains...")
producers = {out: n for n in g.node for out in n.output}
consumers = {}
for n in g.node:
    for inp in n.input:
        consumers.setdefault(inp, []).append(n)

in_nodes = [n for n in g.node if n.op_type == "InstanceNormalization"]
print(f"Found {len(in_nodes)} InstanceNorm nodes to decompose.")

for idx, in_node in enumerate(in_nodes):
    prefix = f"safe_gn_{idx}"
    
    r_pre = producers[in_node.input[0]]
    r_post_name = in_node.output[0]
    r_post = consumers[r_post_name][0]
    
    nodes_to_remove.extend([r_pre, in_node, r_post])
    
    shape_pre = inits[r_pre.input[1]]
    shape_post = inits[r_post.input[1]]
    
    G = int(shape_pre[1])
    C = int(shape_post[1])
    H = int(shape_post[2])
    W = int(shape_post[3])
    C_G = C // G
    
    x = r_pre.input[0]
    final_out = r_post.output[0]
    
    # Constants
    cg_shape_name = f"{prefix}_cg_shape"
    tile_rep_name = f"{prefix}_tile_rep"
    c_shape_name = f"{prefix}_c_shape"
    eps_name = f"{prefix}_eps"
    
    for name, data, dtype, shape in [
        (cg_shape_name, [1, G, C_G, 1], TensorProto.INT64, [4]),
        (tile_rep_name, [1, 1, C_G, 1], TensorProto.INT64, [4]),
        (c_shape_name, [1, C, 1, 1], TensorProto.INT64, [4]),
        (eps_name, [1e-5], TensorProto.FLOAT, [1, 1, 1, 1]),
    ]:
        t = helper.make_tensor(name, dtype, shape, data)
        new_inits.append(t)
    
    new_nodes.extend([
        helper.make_node("ReduceMean", [x], [f"{prefix}_mhw"], axes=[2, 3], keepdims=1, name=f"{prefix}_mhw"),
        helper.make_node("Reshape", [f"{prefix}_mhw", cg_shape_name], [f"{prefix}_mcg"], name=f"{prefix}_mcg"),
        helper.make_node("ReduceMean", [f"{prefix}_mcg"], [f"{prefix}_mg"], axes=[2], keepdims=1, name=f"{prefix}_mg"),
        helper.make_node("Tile", [f"{prefix}_mg", tile_rep_name], [f"{prefix}_mtile"], name=f"{prefix}_mtile"),
        helper.make_node("Reshape", [f"{prefix}_mtile", c_shape_name], [f"{prefix}_mean"], name=f"{prefix}_mean"),
        
        helper.make_node("Sub", [x, f"{prefix}_mean"], [f"{prefix}_diff"], name=f"{prefix}_diff"),
        helper.make_node("Mul", [f"{prefix}_diff", f"{prefix}_diff"], [f"{prefix}_sq"], name=f"{prefix}_sq"),
        
        helper.make_node("ReduceMean", [f"{prefix}_sq"], [f"{prefix}_vhw"], axes=[2, 3], keepdims=1, name=f"{prefix}_vhw"),
        helper.make_node("Reshape", [f"{prefix}_vhw", cg_shape_name], [f"{prefix}_vcg"], name=f"{prefix}_vcg"),
        helper.make_node("ReduceMean", [f"{prefix}_vcg"], [f"{prefix}_vg"], axes=[2], keepdims=1, name=f"{prefix}_vg"),
        helper.make_node("Tile", [f"{prefix}_vg", tile_rep_name], [f"{prefix}_vtile"], name=f"{prefix}_vtile"),
        helper.make_node("Reshape", [f"{prefix}_vtile", c_shape_name], [f"{prefix}_var"], name=f"{prefix}_var"),
        
        helper.make_node("Add", [f"{prefix}_var", eps_name], [f"{prefix}_var_eps"], name=f"{prefix}_var_eps"),
        helper.make_node("Sqrt", [f"{prefix}_var_eps"], [f"{prefix}_std"], name=f"{prefix}_std"),
        helper.make_node("Div", [f"{prefix}_diff", f"{prefix}_std"], [final_out], name=f"{prefix}_norm"),
    ])

print(f"Total nodes to remove: {len(nodes_to_remove)}")
print(f"Total new nodes to add: {len(new_nodes)}")

# Apply graph edits
for n in nodes_to_remove:
    g.node.remove(n)
g.node.extend(new_nodes)
g.initializer.extend(new_inits)

# Clean stale value info and infer shapes
del g.value_info[:]
model = onnx.shape_inference.infer_shapes(model)

r5 = [(vi.name, [d.dim_value for d in vi.type.tensor_type.shape.dim])
      for vi in list(model.graph.value_info) + list(model.graph.output)
      if len(vi.type.tensor_type.shape.dim) >= 5]
print(f"Rank>=5 tensors remaining: {len(r5)}")
for n, s in r5:
    print("   ", n, s)

onnx.save(model, DST)
print(f"Saved rewritten ONNX model to {DST}!")

print("Running full graph parity check on host ORT...")
inp_raw = np.fromfile(r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\light-colorizer-512-trial\probe_input_512.raw",
                      np.float32).reshape(1, 1, 512, 512)
sess_src = ort.InferenceSession(SRC, providers=["CPUExecutionProvider"])
ref_out = sess_src.run(None, {"L_bw": inp_raw})[0]

sess_test = ort.InferenceSession(DST, providers=["CPUExecutionProvider"])
test_out = sess_test.run(None, {"L_bw": inp_raw})[0]

max_diff = float(np.max(np.abs(ref_out - test_out)))
mse = float(np.mean((ref_out.astype(np.float64) - test_out.astype(np.float64))**2))
psnr = 10 * np.log10(4.0 / mse) if mse > 0 else 999.0
print(f"FULL GRAPH PARITY vs ORIGINAL: max_diff={max_diff:.4e}, PSNR={psnr:.2f} dB")
assert psnr > 90.0, "PSNR below 90 dB!"
print("FULL GRAPH VERIFICATION PASSED PERFECTLY!")
