echo "=== system libs ==="
ldconfig -p | grep -e 'libc++' -e 'libatomic' -e 'libunwind'
echo "=== venv module test ==="
python3 -m venv /home/admin1/d_npu_aot/venv && echo VENV_OK || echo VENV_FAIL
ls /home/admin1/d_npu_aot/venv/bin/python3 2>/dev/null && /home/admin1/d_npu_aot/venv/bin/python3 --version
echo "=== cli surface (nightly venv, for reference only) ==="
/home/admin1/.local/share/litert-cli-venv/bin/litert --help 2>&1 | head -25
