#!/bin/bash
set -e
SRC="/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-tachiyomiatvibe-bright-newt-0fa4744e913e/npu_route"
TRIAL="/mnt/c/Users/ADMIN/.traycer/epics/a6ecb13a-1ffe-4afc-9a81-9eff76afeff1/scratch/mcv2-tflite-trial"
mkdir -p /home/admin1/d_npu_aot/scripts /home/admin1/d_npu_aot/in /home/admin1/d_npu_aot/calib
cp "$TRIAL/user_colorize_work_704x512.png" "$TRIAL/user_colorize_work_704x512_28.png" /home/admin1/d_npu_aot/in/
for f in "$SRC"/*.py; do
  tr -d '\r' < "$f" > "/home/admin1/d_npu_aot/$(basename "$f")"
done
for f in "$SRC"/*.sh; do
  tr -d '\r' < "$f" > "/home/admin1/d_npu_aot/scripts/$(basename "$f")"
done
ls /home/admin1/d_npu_aot/in/
echo STAGED
