#!/bin/bash
set -e
docker rm -f litert-build-tmp 2>/dev/null || true
docker run --name litert-build-tmp ubuntu:22.04 bash -c "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3.10-venv python3-pip libc++1 libatomic1 curl ca-certificates unzip xz-utils >/dev/null && echo APT_OK"
docker commit litert-build-tmp litert-compile:22.04
docker rm litert-build-tmp
docker images litert-compile --format '{{.Repository}}:{{.Tag}} {{.Size}}'
