V=/home/admin1/.local/share/litert-cli-venv
echo "=== venv exists? ==="
ls -d "$V" 2>/dev/null || echo "MISSING"
echo "=== bin ==="
ls "$V/bin" 2>/dev/null | head -20
echo "=== python ==="
"$V/bin/python" --version 2>/dev/null
echo "=== litert-ish packages ==="
"$V/bin/pip" list 2>/dev/null | grep -i -e litert -e ai-edge -e qualcomm -e torch
echo "=== sudo ==="
sudo -n true 2>&1 | head -1
