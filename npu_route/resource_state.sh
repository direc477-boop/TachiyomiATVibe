echo "=== my running containers ==="
docker ps --format '{{.ID}} {{.Image}} {{.Status}} {{.RunningFor}}' | grep -e litert -e ubuntu || echo NONE_MINE_RUNNING
echo "=== all running containers ==="
docker ps --format '{{.ID}} {{.Image}} {{.Status}}'
echo "=== my exited containers (should be none, --rm) ==="
docker ps -a --format '{{.Image}} {{.Status}}' | grep -e litert -e 'ubuntu:22.04' | grep -v Up || echo NONE_EXITED
echo "=== stats snapshot (my containers) ==="
CIDS=$(docker ps -q --filter ancestor=litert-compile:22.04)
if [ -n "$CIDS" ]; then docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}' $CIDS; else echo none; fi
echo "=== WSL free memory ==="
free -h | head -3
