#!/bin/bash
mkdir -p /home/admin1/d_npu_aot/sdist_x
tar xzf /home/admin1/d_npu_aot/sdk_sdist.tar.gz -C /home/admin1/d_npu_aot/sdist_x
find /home/admin1/d_npu_aot/sdist_x -type f
echo "=== file contents ==="
find /home/admin1/d_npu_aot/sdist_x -type f -name '*.toml' -o -type f -name '*.py' -o -type f -name '*.cfg' | while read -r f; do
  echo "== $f =="
  cat "$f"
done
