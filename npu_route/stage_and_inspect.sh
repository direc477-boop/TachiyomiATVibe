#!/bin/bash
set -e
mkdir -p /home/admin1/d_npu_aot/scripts
SRC="/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-tachiyomiatvibe-bright-newt-0fa4744e913e/npu_route"
for f in "$SRC"/*.sh; do
  tr -d '\r' < "$f" > "/home/admin1/d_npu_aot/scripts/$(basename "$f")"
done
ls /home/admin1/d_npu_aot/scripts/
docker run --rm -v /home/admin1/d_npu_aot:/scratch ubuntu:22.04 bash /scratch/scripts/inspect_sdk_pkg.sh
