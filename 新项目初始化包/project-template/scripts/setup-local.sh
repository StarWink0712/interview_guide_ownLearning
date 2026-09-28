#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_DIR}"

./scripts/check-prerequisites.sh

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "已从 .env.example 创建 .env。"
else
  echo ".env 已存在，保持不变。"
fi

set -a
source .env
set +a

docker compose config >/dev/null
./scripts/start-deps.sh
./gradlew test

echo
echo "本地环境初始化完成。"
echo "运行后端:"
echo "  ./scripts/run-backend.sh"
