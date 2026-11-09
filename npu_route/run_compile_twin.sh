#!/bin/bash
set -e
echo "=== AOT COMPILE fp16weights deployment twin -> SM8650 ==="
/scratch/venv/bin/python3 /scratch/compile_b6.py /scratch/in/mcv2-fp32-b6-int32idx_fp16weights.tflite /scratch/out
echo "=== stage twin into handoff ==="
/scratch/venv/bin/python3 - <<'EOF'
import hashlib, os, shutil
SRC = "/scratch/out/mcv2-fp32-b6-int32idx_fp16weights_Qualcomm_SM8650_apply_plugin.tflite"
HANDOFF = "/scratch/npu-handoff"
def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 22), b""):
            h.update(c)
    return h.hexdigest()
dst = os.path.join(HANDOFF, os.path.basename(SRC))
shutil.copyfile(SRC, dst)
size, digest = os.path.getsize(dst), sha256(dst)
sec = [
    "## compiled artifact (deployment twin)",
    f"- file: {os.path.basename(SRC)}",
    f"- sha256: {digest}",
    f"- bytes: {size}",
    "- provenance: preserved B6-v1 fp16weights twin (sha256 21ece2023f859439e281308b5eb7f38cca69d50502ae5501c4ec84aa837aed15)",
    "- compile: identical to FP32 diagnostic (ai-edge-litert 2.3.0, SM8650/v75, QAIRT 2.50.0.260828, defaults)",
    "- role: deployment candidate per B's nomination (FP32 diagnostic first, twin on compile viability)",
    "",
]
with open(os.path.join(HANDOFF, "MANIFEST.md"), "a", newline="\n") as f:
    f.write("\n".join(sec))
print(f"[twin] staged: {size} B sha256={digest}")
EOF
ls -la /scratch/npu-handoff/
echo TWIN_DONE
