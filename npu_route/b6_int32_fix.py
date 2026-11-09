"""B6 'int32idx': census every INT64 tensor in the B5 flatbuffers and rewrite the whole class
to INT32 (values unchanged, all <= 1023) - litert-gpu 2.3.0 rejects INT64 GATHER indices on
device ('Only accept INT32 indices') and the same class of int64 index/perm/shape tensors
(TRANSPOSE perms, SLICE begin/size, RESHAPE shape) may hit the same GPU-side restriction.
Surgical ModelT patch of the verified B5 files; no re-conversion. Verifies bitwise CPU parity
of the patched fp32 twin vs the original B5 fp32."""
import hashlib
import json
import time
from pathlib import Path

import numpy as np
from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.interpreter import Interpreter
import flatbuffers

TRIAL = Path(r"C:\Users\ADMIN\.traycer\epics\a6ecb13a-1ffe-4afc-9a81-9eff76afeff1\scratch\mcv2-tflite-trial")
VDIR = TRIAL / "gpu_variants"
OUT6 = VDIR / "out_b6"
OUT6.mkdir(exist_ok=True)
ns = {}
exec((VDIR / "out_b1c" / "schema_generated.py").read_text(encoding="utf-8"), ns)
T = {0: "f32", 1: "f16", 2: "i32", 3: "u8", 5: "i64", 9: "f64"}
OPN = {v: k for k, v in vars(s.BuiltinOperator).items() if isinstance(v, int)}
H, W = 704, 512


def load(path: Path):
    model = ns["Model"].GetRootAs(path.read_bytes(), 0)
    return ns["ModelT"].InitFromObj(model)


def op_type(mt, op):
    oc = mt.operatorCodes[op.opcodeIndex]
    code = oc.builtinCode if oc.builtinCode != 0 else oc.deprecatedBuiltinCode
    return OPN.get(code, f"CUSTOM({code})")


def nm(x) -> str:
    return x.decode() if isinstance(x, bytes) else x


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
        rows.append({"index": idx, "name": nm(t.name)[:60],
                     "shape": [int(d) for d in t.shape], "consumers": sorted(set(consumers.get(idx, []))),
                     "has_data": data is not None})
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
            raise SystemExit(f"int64 value out of int32 range in {t.Name()}")
        mt.buffers[t.buffer].data = vals.astype("<i4").tobytes()
        t.type = s.TensorType.INT32
        patched.append(t.name[:60])
    return patched


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
    inter.set_tensor(inter.get_input_details()[0]["index"], x.copy())
    t0 = time.perf_counter()
    inter.invoke()
    return inter.get_tensor(inter.get_output_details()[0]["index"]).astype(np.float32), time.perf_counter() - t0


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    report = {}
    pairs = [
        (VDIR / "out_b5" / "mcv2-fp32-b5-d2s4d_float32.tflite",
         OUT6 / "mcv2-fp32-b6-int32idx_float32.tflite"),
        (VDIR / "out_b5" / "mcv2-fp32-b5-d2s4d_fp16weights.tflite",
         OUT6 / "mcv2-fp32-b6-int32idx_fp16weights.tflite"),
    ]
    src_outputs = {}
    for label, x in (("owner_pattern_grid", owner_pattern()), ("random_seed1234", random_input())):
        out, _ = run_tflite(pairs[0][0], x)
        src_outputs[label] = out

    for src, dst in pairs:
        mt = load(src)
        before = census_i64(mt)
        print(f"\n== {src.name}: {len(before)} INT64 tensors")
        for r in before:
            print("   ", json.dumps(r))
        patched = int32ify(mt)
        after = census_i64(mt)
        pack(mt, dst)
        entry = {"src_sha256": sha256(src), "dst_sha256": sha256(dst),
                 "dst_bytes": dst.stat().st_size,
                 "patched_tensors": patched, "int64_remaining": len(after)}
        print(f"patched {len(patched)} tensors -> {dst.name} "
              f"({entry['dst_bytes']} bytes, sha {entry['dst_sha256'][:16]}), int64 remaining: {len(after)}")
        report[dst.name] = entry

    # CPU parity: patched fp32 twin must reproduce B5 outputs bitwise (values unchanged).
    parity = {}
    for label, x in (("owner_pattern_grid", owner_pattern()), ("random_seed1234", random_input())):
        out, lat = run_tflite(OUT6 / "mcv2-fp32-b6-int32idx_float32.tflite", x)
        ref = src_outputs[label]
        d = float(np.max(np.abs(ref - out)))
        sha = hashlib.sha256(np.ascontiguousarray(out, dtype=np.float32).tobytes()).hexdigest()
        parity[label] = {"max_abs_vs_b5": d, "bitwise": bool(np.array_equal(ref, out)),
                         "sha256_le_bytes": sha, "invoke_s": round(lat, 3),
                         "stats": {"min": float(out.min()), "max": float(out.max()),
                                   "mean": float(out.mean()), "std": float(out.std())}}
        print(f"parity/{label}: max_abs_vs_b5={d:.3e} bitwise={parity[label]['bitwise']} sha={sha[:16]}")
    report["cpu_parity_fp32_twin"] = parity
    (VDIR / "b6_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("report written:", VDIR / "b6_report.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
