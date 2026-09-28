#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_DIR}"

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

docker compose up -d postgres

echo "等待 PostgreSQL 健康检查..."

for attempt in {1..30}; do
  if docker compose exec -T postgres \
      pg_isready -U "${POSTGRES_USER:-postgres}" \
      -d "${POSTGRES_DB:-interview_guide_rebuild}" >/dev/null 2>&1; then
    echo "PostgreSQL 已就绪。"
    exit 0
  fi

  sleep 2
done

echo "PostgreSQL 未在预期时间内就绪。"
docker compose ps
exit 1
