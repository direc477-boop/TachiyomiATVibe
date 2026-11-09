#!/bin/bash
set -e
PY=/scratch/venv/bin/python3
echo "=== [1/6] extract release zips (provenance: filenames preserved) ==="
cd /scratch/quicksrnet/dl
$PY -I -c "import zipfile; zipfile.ZipFile('qsrnetlarge-tflite-float.zip').extractall('float'); zipfile.ZipFile('qsrnetlarge-tflite-w8a8.zip').extractall('w8a8'); print('extracted')"
find float w8a8 -type f -exec ls -la {} \;
sha256sum float/*/* w8a8/*/*
cat float/*/metadata.json
cat w8a8/*/metadata.json
cp float/*/*.tflite /scratch/quicksrnet/in/QuickSRNetLarge_float.tflite
cp w8a8/*/*.tflite /scratch/quicksrnet/in/QuickSRNetLarge_w8a8.tflite
sha256sum /scratch/quicksrnet/in/*.tflite
echo "=== [2/6] inspect + numeric smoke: float ==="
$PY -I /scratch/inspect_qsrnet.py /scratch/quicksrnet/in/QuickSRNetLarge_float.tflite
echo "=== [3/6] inspect + numeric smoke: w8a8 ==="
$PY -I /scratch/inspect_qsrnet.py /scratch/quicksrnet/in/QuickSRNetLarge_w8a8.tflite
echo "=== [4/6] AOT compile float -> SM8650 ==="
$PY -I /scratch/compile_b6.py /scratch/quicksrnet/in/QuickSRNetLarge_float.tflite /scratch/quicksrnet/out
echo "=== [5/6] AOT compile w8a8 -> SM8650 ==="
$PY -I /scratch/compile_b6.py /scratch/quicksrnet/in/QuickSRNetLarge_w8a8.tflite /scratch/quicksrnet/out
echo "=== [6/6] out-dir + container memory peak ==="
ls -la /scratch/quicksrnet/out/
sha256sum /scratch/quicksrnet/out/*.tflite
cat /sys/fs/cgroup/memory.peak 2>/dev/null || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null || echo PEAK_UNAVAILABLE
echo QSRNET_PROBE_DONE
