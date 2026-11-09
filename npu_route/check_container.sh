docker ps --format '{{.ID}} {{.Image}} {{.Status}}'
C=$(docker ps -q | head -1)
if [ -n "$C" ]; then
  echo "=== top ==="
  docker top "$C" | head -10
  echo "=== recent container stdout (if logged) ==="
  docker logs --tail 5 "$C" 2>&1 | head -10
else
  echo "NO RUNNING CONTAINER"
  echo "=== venv state on mount ==="
  ls /home/admin1/d_npu_aot/venv/bin 2>/dev/null | head -5
  du -sh /home/admin1/d_npu_aot/venv 2>/dev/null
fi
