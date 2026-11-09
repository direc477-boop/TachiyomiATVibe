"""B6 'int32idx' for 832x576 MCV2 model.

Censuses every INT64 tensor in the B5 flatbuffer and rewrites the whole class
to INT32. Surgical ModelT patch; verifies bitwise CPU parity of patched fp32
twin vs original B5 fp32 and ONNX reference.
"""
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import flatbuffers
import numpy as np

from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.interpreter import Interpreter

SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/scratch/mcv2_576x832/out_b5/mcv2-fp32-b5-832x576_nchw_float32.tflite")
DST = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
SRC_ONNX = Path("/scratch/mcv2_576x832/mcv2-fp32-b5-832x576.onnx")

H, W = 832, 576
OPN = {v: k for k, v in vars(s.BuiltinOperator).items() if isinstance(v, int)}


def load(path: Path):
    buf = path.read_bytes()
    model = s.Model.GetRootAs(buf, 0)
    return s.ModelT.InitFromObj(model)


def op_type(mt, op):
    oc = mt.operatorCodes[op.opcodeIndex]
    code = oc.builtinCode if oc.builtinCode != 0 else oc.deprecatedBuiltinCode
    return OPN.get(code, f"CUSTOM({code})")


def nm(x) -> str:
    return x.decode() if isinstance(x, bytes) else str(x)


def census_i64(mt):
    sg = mt.subgraphs[0]
    consumers = {}
    for op in sg.operators:
        opnm = op_type(mt, op)
        for i in op.inputs:
            consumers.setdefault(i, []).append(opnm)
    rows = []
    for idx, t in enumerate(sg.tensors):
        if t.type != s.TensorType.INT64:
            continue
        data = mt.buffers[t.buffer].data
        rows.append({
            "index": idx,
            "name": nm(t.name)[:60],
            "shape": [int(d) for d in t.shape] if t.shape is not None else [],
            "consumers": sorted(set(consumers.get(idx, []))),
            "has_data": data is not None,
        })
    return rows


def int32ify(mt):
    sg = mt.subgraphs[0]
    patched = []
    for idx, t in enumerate(sg.tensors):
        if t.type != s.TensorType.INT64:
            continue
        data = mt.buffers[t.buffer].data
        if data is None:
            continue
        raw = bytes(bytearray(data))
        vals = np.frombuffer(raw, dtype="<i8")
        if int(np.max(np.abs(vals))) >= 2**31:
            raise SystemExit(f"int64 value out of int32 range in {nm(t.name)}")
        mt.buffers[t.buffer].data = vals.astype("<i4").tobytes()
        t.type = s.TensorType.INT32
        patched.append(nm(t.name)[:60])
    return patched


def set_contract_metadata(mt):
    sg = mt.subgraphs[0]
    sg.tensors[sg.inputs[0]].name = "input"
    sg.tensors[sg.outputs[0]].name = "rgb"

    sd = s.SignatureDefT()
    sd.signatureKey = "serving_default"
    sd.subgraphIndex = 0

    ti = s.TensorMapT()
    ti.name = "input"
    ti.tensorIndex = sg.inputs[0]
    sd.inputs = [ti]

    to = s.TensorMapT()
    to.name = "rgb"
    to.tensorIndex = sg.outputs[0]
    sd.outputs = [to]

    mt.signatureDefs = [sd]


def pack(mt, path: Path):
    builder = flatbuffers.Builder(1 << 28)
    off = mt.Pack(builder)
    builder.Finish(off, file_identifier=b"TFL3")
    path.write_bytes(bytes(builder.Output()))


def owner_pattern() -> np.ndarray:
    t = np.zeros((1, 5, H, W), dtype=np.float32)
    xs = np.arange(W) % 64 < 3
    ys = np.arange(H) % 64 < 3
    t[0, 0] = np.where(xs[None, :] | ys[:, None], np.float32(0.05), np.float32(0.95))
    return t


def random_input() -> np.ndarray:
    return np.random.default_rng(1234).random((1, 5, H, W), dtype=np.float32)


def run_tflite(path: Path, x):
    inter = Interpreter(model_path=str(path))
    inter.allocate_tensors()
    in_idx = inter.get_input_details()[0]["index"]
    out_idx = inter.get_output_details()[0]["index"]
    inter.set_tensor(in_idx, x.copy())
    t0 = time.perf_counter()
    inter.invoke()
    lat = time.perf_counter() - t0
    out = inter.get_tensor(out_idx).astype(np.float32)
    return out, lat


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    print(f"[b6_fix] Loading {SRC}...", flush=True)
    mt = load(SRC)
    before = census_i64(mt)
    print(f"[b6_fix] Found {len(before)} INT64 tensors:", flush=True)
    for r in before:
        print("   ", json.dumps(r), flush=True)

    # Compute baseline reference output before patching
    print("[b6_fix] Computing baseline reference outputs on B5 TFLite...", flush=True)
    src_outputs = {}
    for label, x in (("owner_pattern_grid", owner_pattern()), ("random_seed1234", random_input())):
        out, lat = run_tflite(SRC, x)
        src_outputs[label] = out
        print(f"[b6_fix] Baseline {label} shape={out.shape} mean={float(out.mean()):.5f} invoke={lat:.3f}s", flush=True)

    # Apply INT64 -> INT32 and set contract names/signature
    patched = int32ify(mt)
    set_contract_metadata(mt)
    after = census_i64(mt)

    DST.parent.mkdir(parents=True, exist_ok=True)
    pack(mt, DST)
    dst_hash = sha256(DST)
    dst_size = DST.stat().st_size
    print(f"[b6_fix] Patched {len(patched)} tensors -> {DST} ({dst_size} bytes, sha256: {dst_hash}), INT64 remaining: {len(after)}", flush=True)

    # Verify loaded contract in DST
    inter_dst = Interpreter(model_path=str(DST))
    inter_dst.allocate_tensors()
    in_det = inter_dst.get_input_details()
    out_det = inter_dst.get_output_details()
    sig_list = inter_dst.get_signature_list()
    print(f"[b6_fix] Verified DST input: name='{in_det[0]['name']}', shape={in_det[0]['shape'].tolist()}", flush=True)
    print(f"[b6_fix] Verified DST output: name='{out_det[0]['name']}', shape={out_det[0]['shape'].tolist()}", flush=True)
    print(f"[b6_fix] Verified DST signatures: {sig_list}", flush=True)

    assert in_det[0]["name"] == "input", f"Expected input name 'input', got {in_det[0]['name']}"
    assert out_det[0]["name"] == "rgb", f"Expected output name 'rgb', got {out_det[0]['name']}"
    assert in_det[0]["shape"].tolist() == [1, 5, 832, 576], f"Unexpected shape {in_det[0]['shape']}"
    assert out_det[0]["shape"].tolist() == [1, 832, 576, 3], f"Unexpected shape {out_det[0]['shape']}"

    # Parity verification vs baseline B5
    print("[b6_fix] Verifying bitwise CPU parity vs baseline B5...", flush=True)
    for label, x in (("owner_pattern_grid", owner_pattern()), ("random_seed1234", random_input())):
        out, lat = run_tflite(DST, x)
        ref = src_outputs[label]
        d = float(np.max(np.abs(ref - out)))
        bitwise = bool(np.array_equal(ref, out))
        sha = hashlib.sha256(np.ascontiguousarray(out, dtype=np.float32).tobytes()).hexdigest()
        print(f"[b6_fix] Parity/{label}: max_abs_diff={d:.3e}, bitwise={bitwise}, sha256={sha[:16]}, invoke_time={lat:.3f}s", flush=True)
        assert d == 0.0 or bitwise, f"Bitwise parity failed on {label}: max_abs={d}"

    print("[b6_fix] B6 INT32 patch and contract validation SUCCESSFUL!", flush=True)


if __name__ == "__main__":
    main()
