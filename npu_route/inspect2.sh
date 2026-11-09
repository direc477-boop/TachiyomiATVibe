SP=/scratch/venv/lib/python3.10/site-packages
echo "=== rest of sdk __init__.py ==="
sed -n '80,160p' "$SP/ai_edge_litert_sdk_qualcomm/__init__.py"
echo "=== ai_edge_litert compiler plugin .so files ==="
find "$SP/ai_edge_litert" -name '*.so' | head
echo "=== doc lines 40-60 (install section, uncensored) ==="
tr -d '\r' < /scratch/python_aot.md | sed -n '40,70p'
