#!/bin/bash
set -e
H=/home/admin1/d_npu_aot/npu-handoff
EPIC=/mnt/c/Users/ADMIN/.traycer/epics/8a8756ac-c25a-4455-8a5d-da6fb2b75972/scratch/npu-handoff
mkdir -p "$(dirname "$EPIC")"
rm -rf "$EPIC"
cp -r "$H" "$EPIC"
ls -la "$EPIC"
# preserve compile + verify logs next to scratch outputs
mkdir -p /home/admin1/d_npu_aot/logs
echo "log copy target prepared"
sha256sum "$EPIC"/*.so "$EPIC"/*.tflite
