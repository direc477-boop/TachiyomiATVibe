#!/bin/bash
set -e
cd /scratch
curl -sL -o sdk_sdist.tar.gz "https://files.pythonhosted.org/packages/source/a/ai-edge-litert-sdk-qualcomm/ai_edge_litert_sdk_qualcomm-2.3.0.tar.gz" || true
if [ ! -s sdk_sdist.tar.gz ]; then
  echo "direct URL failed, asking pip for the real URL"
  /scratch/venv/bin/pip download ai-edge-litert-sdk-qualcomm==2.3.0 --no-deps --no-binary :all: -d /scratch/sdist_dl
  tar tzf /scratch/sdist_dl/*.tar.gz | head
  tar xzf /scratch/sdist_dl/*.tar.gz -C /scratch/sdist_dl
  SD=$(find /scratch/sdist_dl -maxdepth 1 -type d -name '*' | tail -1)
else
  mkdir -p /scratch/sdist_x && tar xzf sdk_sdist.tar.gz -C /scratch/sdist_x
  SD=$(find /scratch/sdist_x -maxdepth 1 -type d | tail -1)
fi
echo "=== sdist root: $SD ==="
ls -la "$SD"
echo "=== pyproject.toml ==="
cat "$SD/pyproject.toml" 2>/dev/null | head -50
echo "=== setup.py ==="
cat "$SD/setup.py" 2>/dev/null | head -120
