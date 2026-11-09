#!/bin/bash
set -e
echo "=== verify compiled artifact ==="
/scratch/venv/bin/python3 /scratch/verify_compile_output.py /scratch/out/mcv2-fp32-b6-int32idx_float32_Qualcomm_SM8650_apply_plugin.tflite
echo "=== stage handoff (libs + artifact + manifest) ==="
/scratch/venv/bin/python3 /scratch/stage_handoff.py /scratch/out/mcv2-fp32-b6-int32idx_float32_Qualcomm_SM8650_apply_plugin.tflite
echo "=== handoff dir listing ==="
ls -la /scratch/npu-handoff/
echo HANDOFF_STAGED
