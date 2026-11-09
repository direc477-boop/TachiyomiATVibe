#!/bin/bash
set -e
cp /scratch/populate_sdk_data.py /tmp/ 2>/dev/null || true
/scratch/venv/bin/python3 -c "import sys; print('venv python:', sys.version)"
echo "=== introspect pinned API ==="
/scratch/venv/bin/python3 /scratch/scripts/introspect_api.py 2>&1 | head -40
echo "=== plugin linkage ==="
ldd /scratch/venv/lib/python3.10/site-packages/ai_edge_litert/vendors/qualcomm/compiler/libLiteRtCompilerPlugin_Qualcomm.so 2>&1 | head -15
