#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_DIR}"

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

./scripts/check-prerequisites.sh
docker compose config >/dev/null
./gradlew test

echo "配置检查和自动化测试通过。"
