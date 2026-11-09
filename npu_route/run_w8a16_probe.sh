#!/bin/bash
set -e
echo "=== [1/4] install litert-quantizer + pillow into pinned venv ==="
/scratch/venv/bin/pip install -q litert-quantizer pillow 2>&1 | tail -3
/scratch/venv/bin/pip list 2>/dev/null | grep -i -e quantizer -e pillow
echo "=== [2/4] generate 9 smoke calibration inputs ==="
/scratch/venv/bin/python3 /scratch/gen_calib_inputs.py /scratch/calib/calib_inputs.npz
echo "=== [3/4] quantize static_wi8_ai16 ==="
/scratch/venv/bin/python3 /scratch/quantize_w8a16.py
echo "=== [4/4] AOT compile the W8A16 model -> SM8650 ==="
/scratch/venv/bin/python3 /scratch/compile_b6.py /scratch/out/mcv2_b6_w8a16.tflite /scratch/out
echo "=== container memory peak (bytes) ==="
cat /sys/fs/cgroup/memory.peak 2>/dev/null || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null || echo PEAK_UNAVAILABLE
echo W8A16_PROBE_DONE
