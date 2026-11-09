#!/bin/bash
set -e
echo "=== [1/4] populating SDK data dir from zip ==="
/scratch/venv/bin/python3 /scratch/populate_sdk_data.py /scratch/downloads/qairt_2.50.0.zip
echo "=== [2/4] SDK path + license preservation ==="
/scratch/venv/bin/python3 - <<'EOF'
import ai_edge_litert_sdk_qualcomm as q
p = q.path_to_sdk_libs()
print("[sdk] path_to_sdk_libs:", p)
assert p and p.is_dir(), "SDK libs dir still missing"
import os
root = p.parent.parent
lics = []
for d, _, fs in os.walk(root):
    for f in fs:
        low = f.lower()
        if any(k in low for k in ("eula", "license", "legal", "notice")):
            lics.append(os.path.join(d, f))
print("[sdk] license/legal files found:", len(lics))
for l in lics[:12]:
    print("   ", os.path.relpath(l, root))
EOF
echo "=== [3/4] introspect with SDK present ==="
/scratch/venv/bin/python3 /scratch/introspect_api.py
echo "=== [4/4] AOT COMPILE B6-v1 -> SM8650 ==="
mkdir -p /scratch/out
/scratch/venv/bin/python3 /scratch/compile_b6.py /scratch/in/mcv2-fp32-b6-int32idx_float32.tflite /scratch/out
echo "ALL_STAGES_DONE"
