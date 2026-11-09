SP=/scratch/venv/lib/python3.10/site-packages
echo "=== installed sdk package tree ==="
find "$SP/ai_edge_litert_sdk_qualcomm" -maxdepth 3 | head -30
echo "=== __init__.py (first 80 lines) ==="
head -80 "$SP/ai_edge_litert_sdk_qualcomm/__init__.py" 2>/dev/null
echo "=== any download/setup scripts in package ==="
ls "$SP/ai_edge_litert_sdk_qualcomm/" 2>/dev/null
echo "=== compiler plugin present in ai_edge_litert? ==="
find "$SP/ai_edge_litert" -name '*.so' | head -10
echo "=== pip cache: where did the sdist build download things ==="
ls /root/.cache/pip 2>/dev/null | head -5
du -sh /root/.cache/pip 2>/dev/null
