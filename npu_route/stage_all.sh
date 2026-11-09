#!/bin/bash
set -e
SRC="/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-tachiyomiatvibe-bright-newt-0fa4744e913e/npu_route"
mkdir -p /home/admin1/d_npu_aot/scripts
for f in "$SRC"/*.sh; do
  tr -d '\r' < "$f" > "/home/admin1/d_npu_aot/scripts/$(basename "$f")"
done
for f in "$SRC"/*.py; do
  tr -d '\r' < "$f" > "/home/admin1/d_npu_aot/$(basename "$f")"
done
echo STAGED
ls /home/admin1/d_npu_aot/scripts/ | tail -6
