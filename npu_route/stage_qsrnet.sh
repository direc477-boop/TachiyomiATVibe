#!/bin/bash
set -e
DEST='/mnt/c/Users/ADMIN/.traycer/epics/8a8756ac-c25a-4455-8a5d-da6fb2b75972/scratch/npu-handoff-qsrnet'
mkdir -p "$DEST"
cp /home/admin1/d_npu_aot/quicksrnet/out/QuickSRNetLarge_float_Qualcomm_SM8650_apply_plugin.tflite "$DEST/"
cp /home/admin1/d_npu_aot/quicksrnet/out/QuickSRNetLarge_w8a8_Qualcomm_SM8650_apply_plugin.tflite "$DEST/"
cp /home/admin1/d_npu_aot/quicksrnet/in/QuickSRNetLarge_float.tflite "$DEST/"
cp /home/admin1/d_npu_aot/quicksrnet/in/QuickSRNetLarge_w8a8.tflite "$DEST/"
cp /home/admin1/d_npu_aot/quicksrnet/dl/float/quicksrnetlarge-tflite-float/metadata.json "$DEST/metadata-float.json"
cp /home/admin1/d_npu_aot/quicksrnet/dl/w8a8/quicksrnetlarge-tflite-w8a8/metadata.json "$DEST/metadata-w8a8.json"
cd "$DEST"
ls -la
sha256sum *.tflite *.json
echo STAGE_QSRNET_DONE
