C=$(docker ps -q --filter ancestor=ubuntu:22.04 | head -1)
echo "container: $C"
docker top "$C" | tail -5
echo "=== container net stats (eth0) ==="
docker exec "$C" bash -c "cat /proc/net/dev | grep -e eth0 -e Inter; ls /var/cache/apt/archives/*.deb 2>/dev/null | wc -l; ls -la /var/cache/apt/archives/partial 2>/dev/null | tail -3"
