set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq python3.10-venv python3-pip libc++1 libatomic1 curl ca-certificates unzip xz-utils >/dev/null
echo "=== apt done ==="
rm -rf /scratch/venv
python3 -m venv /scratch/venv
/scratch/venv/bin/pip install -q --upgrade pip setuptools wheel
echo "=== base pip done, installing litert + qualcomm sdk (downloads QAIRT) ==="
/scratch/venv/bin/pip install ai-edge-litert==2.3.0 ai-edge-litert-sdk-qualcomm==2.3.0 2>&1 | tail -30
echo "=== installed ==="
/scratch/venv/bin/pip list 2>/dev/null | grep -i -e litert -e ai-edge -e qualcomm
/scratch/venv/bin/python3 -c "import ai_edge_litert_sdk_qualcomm as q; print('sdk libs at:', q.path_to_sdk_libs())"
echo SETUP_DONE
